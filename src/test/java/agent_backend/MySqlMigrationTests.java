package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.TaskLease;
import agent_backend.task.TaskLeaseManager;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.persistence.model.TaskStatus;
import agent_backend.persistence.model.StepStatus;
import agent_backend.persistence.model.ToolCallStatus;
import agent_backend.tool.ToolInvocation;
import agent_backend.tool.ToolCall;
import agent_backend.tool.SequentialToolBatchExecutor;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.tool.result.ToolFailure;
import agent_backend.toolRegistry.ToolRegistry;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Uses a disposable MySQL instance, never the developer's configured database. */
@SpringBootTest(properties = "agent.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationTests {
    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("agent_backend_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskStore tasks;
    @Autowired TaskLeaseManager leases;
    @Autowired StepStore steps;
    @Autowired ToolInvocationStore invocations;
    @Autowired LlmClient client;
    @Autowired ToolRegistry registry;

    @Test
    void bootRunsMigrationAndSecondMigrationDoesNotRecreateTables() {
        assertEquals("3", flyway.info().current().getVersion().getVersion());
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('agent_task', 'agent_step', 'agent_tool_call')
                """, Integer.class));
        String taskId = createTask();
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM agent_task WHERE task_id = ?", String.class, taskId));
    }

    @Test
    void enforcesRelationshipsAndPerTurnUniqueness() {
        String taskId = createTask();
        String stepId = createStep(taskId, 1);
        assertThrows(DataIntegrityViolationException.class, () -> createStep(taskId, 1));
        assertThrows(DataIntegrityViolationException.class, () -> createStep(id(), 1));
        createCall(stepId, "provider-id", 1);
        assertThrows(DataIntegrityViolationException.class, () -> createCall(stepId, "different-id", 1));
        assertThrows(DataIntegrityViolationException.class, () -> createCall(stepId, "provider-id", 2));
        assertThrows(DataIntegrityViolationException.class, () -> createCall(id(), "provider-id", 1));
        // Protocol IDs are unique within a turn, not globally.
        createCall(createStep(taskId, 2), "provider-id", 1);
    }

    @Test
    void ownedQueryIsScopedAndLegacyRowsRemainQuarantined() {
        String owned = id();
        String alice = "a".repeat(64), bob = "b".repeat(64);
        tasks.createOwned(owned, "private input", alice);
        assertTrue(tasks.findOwned(owned, alice).isPresent());
        assertTrue(tasks.findOwned(owned, bob).isEmpty());
        assertEquals(alice, tasks.findOwner(owned).orElseThrow());
        String legacy = createTask();
        assertTrue(tasks.findOwned(legacy, alice).isEmpty());
        assertTrue(tasks.findOwner(legacy).isEmpty());
    }

    @Test
    void rejectsInvalidStatesOrderingAndJson() {
        String taskId = createTask();
        String stepId = createStep(taskId, 1);
        String callId = createCall(stepId, "provider-id", 1);
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task SET status = 'INVALID' WHERE task_id = ?", taskId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_step SET status = 'PENDING' WHERE step_id = ?", stepId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_tool_call SET status = 'COMPLETED' WHERE invocation_id = ?", callId));
        assertThrows(DataAccessException.class, () -> createStep(taskId, 0));
        assertThrows(DataAccessException.class, () -> createCall(stepId, "another-id", 0));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_tool_call SET arguments = ? WHERE invocation_id = ?", "{incomplete", callId));
    }

    @Test
    void storesUnicodeAndStructuredToolResults() {
        String taskId = createTask();
        String stepId = createStep(taskId, 1);
        String callId = createCall(stepId, "provider-id", 1);
        jdbc.update("UPDATE agent_task SET task_input = ? WHERE task_id = ?", "读取文件 📄", taskId);
        jdbc.update("UPDATE agent_tool_call SET result = ?, status = 'SUCCEEDED' WHERE invocation_id = ?",
                "{\"toolCallId\":\"provider-id\",\"data\":{\"text\":\"你好\"}}", callId);
        assertEquals("读取文件 📄", jdbc.queryForObject(
                "SELECT task_input FROM agent_task WHERE task_id = ?", String.class, taskId));
        assertEquals("你好", jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(result, '$.data.text')) FROM agent_tool_call WHERE invocation_id = ?",
                String.class, callId));
    }

    @Test
    void storesEnforceTerminalStatesAndMapRecords() {
        String taskId = id();
        tasks.create(taskId, "task");
        tasks.start(lease(taskId), 60000);
        assertThrows(IllegalStateException.class, () -> tasks.start(lease(taskId), 60000));
        String stepId = id();
        steps.create(lease(taskId), stepId, 1);
        steps.saveResponse(lease(taskId), stepId, Map.of("finish_reason", "tool_calls"));
        String callId = invocations.createBatch(lease(taskId), stepId,
                List.of(new ToolInvocation("call-1", "test_tool", Map.of()))).getFirst();
        invocations.start(lease(taskId), callId);
        assertThrows(IllegalStateException.class, () -> invocations.start(lease(taskId), callId));
        assertThrows(IllegalStateException.class, () -> steps.complete(lease(taskId), stepId));
        assertThrows(IllegalStateException.class, () -> invocations.finish(lease(taskId), callId,
                new ToolSuccess<>("wrong-id", "test_tool", "ok")));
        invocations.finish(lease(taskId), callId, new ToolSuccess<>("call-1", "test_tool", "ok"));
        assertThrows(IllegalStateException.class, () -> invocations.finish(lease(taskId), callId,
                new ToolFailure<>("call-1", "test_tool", "LATE", "late overwrite")));
        steps.complete(lease(taskId), stepId);
        assertThrows(IllegalStateException.class, () -> steps.saveResponse(lease(taskId), stepId, Map.of()));
        assertThrows(IllegalStateException.class, () -> steps.fail(lease(taskId), stepId, "late"));
        tasks.complete(lease(taskId), "answer");
        assertThrows(IllegalStateException.class, () -> tasks.fail(lease(taskId), "late"));
        assertThrows(IllegalStateException.class, () -> tasks.complete(lease(taskId), "overwrite"));
        assertThrows(IllegalStateException.class, () -> steps.create(lease(taskId), id(), 2));
        assertEquals(TaskStatus.COMPLETED, tasks.find(taskId).orElseThrow().status());
        assertEquals("answer", tasks.find(taskId).orElseThrow().finalAnswer());
        assertEquals(StepStatus.COMPLETED, steps.findByTask(taskId).getFirst().status());
        assertEquals(ToolCallStatus.SUCCEEDED, invocations.findByStep(stepId).getFirst().status());
    }

    @Test
    void wholeBatchRollsBackWhenALaterInsertFails() {
        String taskId = id();
        tasks.create(taskId, "task");
        tasks.start(lease(taskId), 60000);
        String stepId = id();
        steps.create(lease(taskId), stepId, 1);
        var call = new ToolInvocation("duplicate", "test_tool", Map.of());
        assertThrows(DataIntegrityViolationException.class,
                () -> invocations.createBatch(lease(taskId), stepId, List.of(call, call)));
        assertTrue(invocations.findByStep(stepId).isEmpty());
    }

    @Test
    void synchronousRunPersistsToolFailuresAndFinalAnswerOutsideTransactions() {
        LlmClient llm = spy(client);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return toolResponse(2);
        }).doReturn(answerResponse()).when(llm).request(any());
        ToolCall tools = mock(ToolCall.class);
        when(tools.callTool(any(), any(), any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            String callId = invocation.getArgument(0);
            return callId.equals("call-1")
                    ? new ToolFailure<>(callId, "test_tool", "INVALID_ARGUMENT", "invalid value")
                    : new ToolSuccess<>(callId, "test_tool", "ok");
        });
        AgentState state = runner(llm, tools, 2).run("task");
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        var task = tasks.find(state.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, task.status());
        assertEquals("done", task.finalAnswer());
        var savedSteps = steps.findByTask(task.taskId());
        assertEquals(2, savedSteps.size());
        assertTrue(savedSteps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED));
        assertTrue(savedSteps.getFirst().modelResponse().contains("finish_reason"));
        var calls = invocations.findByStep(savedSteps.getFirst().stepId());
        assertEquals(List.of(ToolCallStatus.FAILED, ToolCallStatus.SUCCEEDED),
                calls.stream().map(call -> call.status()).toList());
        assertTrue(calls.getFirst().result().contains("INVALID_ARGUMENT"));
        assertEquals(savedSteps.getFirst().stepId(), state.getSteps().getFirst().getStepId());
    }

    @Test
    void laterExecutionExceptionKeepsPriorResultAndMarksUncertainCall() {
        LlmClient llm = spy(client);
        doReturn(toolResponse(3)).when(llm).request(any());
        ToolCall tools = mock(ToolCall.class);
        when(tools.callTool("call-1", "test_tool", Map.of()))
                .thenAnswer(invocation -> new ToolSuccess<>("call-1", "test_tool", "saved"));
        when(tools.callTool("call-2", "test_tool", Map.of()))
                .thenThrow(new IllegalStateException("execution interrupted"));
        AgentState state = runner(llm, tools, 2).run("task");
        var task = tasks.find(state.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(task.failureReason().contains("execution interrupted"));
        var step = steps.findByTask(task.taskId()).getFirst();
        assertEquals(StepStatus.FAILED, step.status());
        var calls = invocations.findByStep(step.stepId());
        assertEquals(List.of(ToolCallStatus.SUCCEEDED, ToolCallStatus.UNKNOWN, ToolCallStatus.FAILED),
                calls.stream().map(call -> call.status()).toList());
        assertTrue(calls.getFirst().result().contains("saved"));
        assertNull(calls.get(1).result());
        assertNull(calls.get(2).startedAt());
        verify(tools, never()).callTool("call-3", "test_tool", Map.of());
    }

    @Test
    void truncatedResponseIsSavedAndNoToolsAreRegisteredOrExecuted() {
        LlmClient llm = spy(client);
        var response = Map.of("choices", List.of(Map.of("finish_reason", "length", "message",
                Map.of("tool_calls", List.of(Map.of("id", "call-1", "function",
                        Map.of("name", "test_tool", "arguments", "{")))))));
        doReturn(response).when(llm).request(any());
        ToolCall tools = mock(ToolCall.class);
        AgentState state = runner(llm, tools, 2).run("task");
        var task = tasks.find(state.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, task.status());
        var step = steps.findByTask(task.taskId()).getFirst();
        assertEquals(StepStatus.FAILED, step.status());
        assertTrue(step.modelResponse().contains("length"));
        assertTrue(step.failureReason().contains("finish_reason=length"));
        assertTrue(invocations.findByStep(step.stepId()).isEmpty());
        verifyNoInteractions(tools);
    }

    @Test
    void requestFailureAndStepLimitBothLeaveQueryableHistory() {
        LlmClient llm = spy(client);
        doThrow(new IllegalStateException("connection failed")).when(llm).request(any());
        ToolCall tools = mock(ToolCall.class);
        AgentState failed = runner(llm, tools, 2).run("task");
        assertEquals(TaskStatus.FAILED, tasks.find(failed.getTaskId()).orElseThrow().status());
        var failedStep = steps.findByTask(failed.getTaskId()).getFirst();
        assertEquals(StepStatus.FAILED, failedStep.status());
        assertNull(failedStep.modelResponse());

        doReturn(toolResponse(1)).when(llm).request(any());
        when(tools.callTool(any(), any(), any())).thenAnswer(invocation ->
                new ToolSuccess<>("call-1", "test_tool", "saved"));
        AgentState limited = runner(llm, tools, 1).run("task");
        var task = tasks.find(limited.getTaskId()).orElseThrow();
        assertEquals(TaskStatus.FAILED, task.status());
        assertTrue(task.failureReason().contains("maximum steps"));
        var step = steps.findByTask(task.taskId()).getFirst();
        assertEquals(StepStatus.COMPLETED, step.status());
        assertEquals(ToolCallStatus.SUCCEEDED, invocations.findByStep(step.stepId()).getFirst().status());
    }


    @Test
    void pendingScanIsOrderedAndExcludesRunningAndTerminalTasks() {
        String first = id();
        String second = id();
        String running = id();
        String failed = id();
        for (String taskId : List.of(first, second, running, failed)) tasks.create(taskId, "task");
        jdbc.update("UPDATE agent_task SET created_at = '2000-01-01 00:00:00' WHERE task_id = ?", first);
        jdbc.update("UPDATE agent_task SET created_at = '2000-01-02 00:00:00' WHERE task_id = ?", second);
        jdbc.update("UPDATE agent_task SET created_at = '1999-01-01 00:00:00' WHERE task_id IN (?, ?)", running, failed);
        tasks.start(lease(running), 60000);
        tasks.failPending(failed, "failed");
        assertEquals(List.of(first, second), tasks.findPending(2));
        assertEquals(List.of(first), tasks.findPending(1));
        assertThrows(IllegalArgumentException.class, () -> tasks.findPending(0));
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        String taskId = id();
        tasks.create(taskId, "task");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                ready.countDown();
                assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                try {
                    tasks.start(lease(taskId), 60000);
                    return true;
                } catch (IllegalStateException alreadyClaimed) {
                    return false;
                }
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(TaskStatus.RUNNING, tasks.find(taskId).orElseThrow().status());
    }


    @Test
    void expiredLeaseRecoveryPreservesSavedResultsAndFencesEveryLateWriter() {
        String taskId = id();
        var owner = lease(taskId);
        tasks.create(taskId, "task");
        tasks.start(owner, 60000);
        String stepId = id();
        steps.create(owner, stepId, 1);
        steps.saveResponse(owner, stepId, Map.of("response", "saved"));
        var ids = invocations.createBatch(owner, stepId, List.of(
                new ToolInvocation("one", "test", Map.of()),
                new ToolInvocation("two", "test", Map.of()),
                new ToolInvocation("three", "test", Map.of())));
        invocations.start(owner, ids.get(0));
        invocations.finish(owner, ids.get(0), new ToolSuccess<>("one", "test", "saved"));
        invocations.start(owner, ids.get(1));
        jdbc.update("UPDATE agent_task SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE task_id = ?", taskId);
        assertFalse(tasks.renew(owner, 60000));
        assertThrows(agent_backend.persistence.LeaseLostException.class,
                () -> invocations.finish(owner, ids.get(1), new ToolSuccess<>("two", "test", "late")));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> invocations.start(owner, ids.get(2)));
        assertTrue(tasks.findExpired(1000).contains(taskId));
        assertTrue(tasks.recoverExpired(taskId));
        assertFalse(tasks.recoverExpired(taskId));
        assertEquals(TaskStatus.FAILED, tasks.find(taskId).orElseThrow().status());
        assertTrue(tasks.find(taskId).orElseThrow().failureReason().contains("lease expired"));
        assertEquals(StepStatus.FAILED, steps.findByTask(taskId).getFirst().status());
        var calls = invocations.findByStep(stepId);
        assertEquals(List.of(ToolCallStatus.SUCCEEDED, ToolCallStatus.UNKNOWN, ToolCallStatus.FAILED),
                calls.stream().map(call -> call.status()).toList());
        assertTrue(calls.getFirst().result().contains("saved"));
        assertNull(calls.get(1).result());
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> steps.saveResponse(owner, stepId, Map.of()));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> tasks.complete(owner, "late answer"));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> tasks.fail(owner, "overwrite"));
    }

    @Test
    void liveLeaseCanRenewButForeignOwnerCannotWriteOrRecover() {
        String taskId = id();
        var owner = lease(taskId);
        var foreign = new TaskLease(taskId, id());
        tasks.create(taskId, "task");
        tasks.start(owner, 60000);
        assertTrue(tasks.renew(owner, 60000));
        assertFalse(tasks.renew(foreign, 60000));
        assertFalse(tasks.recoverExpired(taskId));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> steps.create(foreign, id(), 1));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> tasks.fail(foreign, "wrong worker"));
        var saved = tasks.find(taskId).orElseThrow();
        assertEquals(owner.workerId(), saved.workerId());
        assertTrue(saved.leaseUntil().isAfter(saved.heartbeatAt()));
    }

    @Test
    void recoveryRollsBackAllChangesIfTaskFinalizationFails() {
        String taskId = id();
        var owner = lease(taskId);
        tasks.create(taskId, "task");
        tasks.start(owner, 60000);
        String stepId = id();
        steps.create(owner, stepId, 1);
        String callId = invocations.createBatch(owner, stepId,
                List.of(new ToolInvocation("call", "test", Map.of()))).getFirst();
        invocations.start(owner, callId);
        jdbc.update("UPDATE agent_task SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE task_id = ?", taskId);
        jdbc.execute("CREATE TRIGGER reject_recovery BEFORE UPDATE ON agent_task FOR EACH ROW BEGIN "
                + "IF NEW.task_id = '" + taskId + "' AND NEW.status = 'FAILED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced failure'; END IF; END");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> tasks.recoverExpired(taskId));
            assertEquals(TaskStatus.RUNNING, tasks.find(taskId).orElseThrow().status());
            assertEquals(StepStatus.RUNNING, steps.findByTask(taskId).getFirst().status());
            assertEquals(ToolCallStatus.RUNNING, invocations.findByStep(stepId).getFirst().status());
        } finally {
            jdbc.execute("DROP TRIGGER reject_recovery");
        }
        assertTrue(tasks.recoverExpired(taskId));
    }

    @Test
    void oldRunningRowsWithoutLeaseAreRecoveredButPendingRowsAreUntouched() {
        String old = createTask();
        String pending = createTask();
        jdbc.update("UPDATE agent_task SET status = 'RUNNING' WHERE task_id = ?", old);
        assertTrue(tasks.recoverExpired(old));
        assertFalse(tasks.recoverExpired(pending));
        assertEquals(TaskStatus.PENDING, tasks.find(pending).orElseThrow().status());
    }

    private AgentRunner runner(LlmClient llm, ToolCall tools, int limit) {
        return new AgentRunner(llm, new SequentialToolBatchExecutor(tools, invocations), registry,
                tasks, steps, invocations, leases, limit);
    }

    private Map<String, Object> toolResponse(int count) {
        var calls = java.util.stream.IntStream.rangeClosed(1, count).mapToObj(index ->
                Map.of("id", "call-" + index, "type", "function", "function",
                        Map.of("name", "test_tool", "arguments", "{}"))).toList();
        return Map.of("choices", List.of(Map.of("finish_reason", "tool_calls", "message", Map.of("tool_calls", calls))));
    }

    private Map<String, Object> answerResponse() {
        return Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "done"))));
    }

    private String createTask() {
        String taskId = id();
        jdbc.update("INSERT INTO agent_task (task_id, task_input) VALUES (?, ?)", taskId, "test task");
        return taskId;
    }

    private String createStep(String taskId, int number) {
        String stepId = id();
        jdbc.update("INSERT INTO agent_step (step_id, task_id, step_number) VALUES (?, ?, ?)", stepId, taskId, number);
        return stepId;
    }

    private String createCall(String stepId, String protocolId, int order) {
        String invocationId = id();
        jdbc.update("""
                INSERT INTO agent_tool_call
                    (invocation_id, step_id, tool_call_id, call_order, tool_name, arguments)
                VALUES (?, ?, ?, ?, 'test_tool', '{}')
                """, invocationId, stepId, protocolId, order);
        return invocationId;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
    private TaskLease lease(String taskId) {
        return new TaskLease(taskId, "11111111-1111-1111-1111-111111111111");
    }
}

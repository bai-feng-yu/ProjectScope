package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import agent_backend.tool.ToolCall;
import agent_backend.tool.SequentialToolBatchExecutor;
import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.toolRegistry.ToolRegistry;
import org.junit.jupiter.api.Test;
import agent_backend.task.TaskLeaseManager;
import agent_backend.task.LeaseSession;
import agent_backend.persistence.TaskLease;

class AgentRuntimeTests {
    private final LlmClient llm = mock(LlmClient.class);
    private final ToolCall tools = mock(ToolCall.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);

    private final agent_backend.persistence.TaskStore tasks = mock(agent_backend.persistence.TaskStore.class);
    private final agent_backend.persistence.StepStore steps = mock(agent_backend.persistence.StepStore.class);
    private final agent_backend.persistence.ToolInvocationStore invocations = mock(agent_backend.persistence.ToolInvocationStore.class);

    private final TaskLeaseManager leases = mock(TaskLeaseManager.class);

    @org.junit.jupiter.api.BeforeEach
    void persistenceSetup() {
        when(leases.acquire(any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            TaskLease lease = new TaskLease(id, "test-worker");
            tasks.start(lease, 60000);
            LeaseSession session = mock(LeaseSession.class);
            when(session.lease()).thenReturn(lease);
            return session;
        });
        when(llm.request(any())).thenReturn(Map.of("test", "response"));
        when(invocations.createBatch(any(), any(), any())).thenAnswer(invocation -> {
            List<?> calls = invocation.getArgument(2);
            return calls.stream().map(call -> java.util.UUID.randomUUID().toString()).toList();
        });
    }

    private AgentRunner runner(int maxSteps) {
        return new AgentRunner(llm, new SequentialToolBatchExecutor(tools, invocations), registry, tasks, steps, invocations, leases, maxSteps);
    }

    private AgentStep step() {
        return new AgentStep("step-1", 1, LlmDecision.toolCall("call-1", "test_tool", Map.of()),
                List.of(new ToolSuccess<>("call-1", "test_tool", "ok")));
    }

    @Test
    void terminalStatesRejectMutationsAndExposeReadOnlyStepSnapshots() {
        for (boolean completed : List.of(true, false)) {
            AgentState state = new AgentState("task");
            var before = state.getSteps();
            state.addStep(step());
            assertTrue(before.isEmpty());
            if (completed) state.complete("done");
            else state.fail("failure");

            assertThrows(IllegalStateException.class, () -> state.addStep(step()));
            assertThrows(IllegalStateException.class, () -> state.complete("changed"));
            assertThrows(IllegalStateException.class, () -> state.fail("changed"));
            assertThrows(IllegalStateException.class, () -> state.setTools(List.of()));
            assertThrows(UnsupportedOperationException.class, () -> state.getSteps().clear());
            assertEquals(completed ? AgentState.AgentStateType.COMPLETED : AgentState.AgentStateType.FAILED,
                    state.getAgentStateType());
            assertEquals(completed ? "done" : null, state.getFinalAnswer());
            assertEquals(completed ? null : "failure", state.getFailureReason());
            assertEquals(1, state.getSteps().size());
        }
    }

    @Test
    void modelFailurePreservesCompletedToolSteps() {
        when(llm.parseDecision(any())).thenReturn(step().getDecision())
                .thenThrow(new IllegalStateException("Invalid model response"));
        when(tools.callTool("call-1", "test_tool", Map.of())).thenAnswer(invocation -> step().getToolResults().getFirst());

        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("IllegalStateException: Invalid model response", state.getFailureReason());
        assertEquals(1, state.getSteps().size());
        assertNull(state.getFinalAnswer());
    }

    @Test
    void unexpectedDispatcherFailureBecomesFailedState() {
        when(llm.parseDecision(any())).thenReturn(step().getDecision());
        when(tools.callTool("call-1", "test_tool", Map.of())).thenThrow(new IllegalStateException());
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("IllegalStateException", state.getFailureReason());
    }

    @Test
    void toolFailureCanBeFollowedBySuccessfulAnswer() {
        when(llm.parseDecision(any())).thenReturn(step().getDecision(), LlmDecision.finalAnswer("Recovered"));
        when(tools.callTool("call-1", "test_tool", Map.of())).thenReturn(
                new ToolFailure<>("call-1", "test_tool", "INVALID_ARGUMENT", "Missing field"));
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        assertEquals("Recovered", state.getFinalAnswer());
        assertNull(state.getFailureReason());
        assertEquals(1, state.getSteps().size());
    }

    @Test
    void stepLimitReturnsFailureWithHistoryInsteadOfThrowing() {
        when(llm.parseDecision(any())).thenReturn(step().getDecision());
        when(tools.callTool("call-1", "test_tool", Map.of())).thenAnswer(invocation -> step().getToolResults().getFirst());
        var state = runner(1).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("Agent exceeded maximum steps: 1", state.getFailureReason());
        assertEquals(1, state.getSteps().size());
    }

    @Test
    void preparationFailureAlsoReturnsFailedState() {
        when(registry.getAllTools()).thenThrow(new IllegalStateException("Registry unavailable"));
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertTrue(state.getFailureReason().contains("Registry unavailable"));
    }

    @Test
    void batchRunsInOrderAndContinuesAfterToolFailure() {
        var calls = List.of(
                new agent_backend.tool.ToolInvocation("first", "test_tool", Map.of("value", 1)),
                new agent_backend.tool.ToolInvocation("second", "test_tool", Map.of("value", 2)));
        when(llm.parseDecision(any())).thenReturn(LlmDecision.toolCalls(calls, null), LlmDecision.finalAnswer("done"));
        when(tools.callTool("first", "test_tool", Map.of("value", 1)))
                .thenReturn(new ToolFailure<>("first", "test_tool", "INVALID_ARGUMENT", "Invalid"));
        when(tools.callTool("second", "test_tool", Map.of("value", 2)))
                .thenAnswer(invocation -> new ToolSuccess<>("second", "test_tool", "ok"));
        var state = runner(2).run("task");
        var order = org.mockito.Mockito.inOrder(tools);
        order.verify(tools).callTool("first", "test_tool", Map.of("value", 1));
        order.verify(tools).callTool("second", "test_tool", Map.of("value", 2));
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        assertEquals(1, state.getSteps().size());
        assertEquals(List.of("first", "second"), state.getSteps().getFirst().getToolResults()
                .stream().map(result -> result.toolCallId()).toList());
    }

    @Test
    void batchRejectsDuplicateIdsAndMismatchedResults() {
        var call = new agent_backend.tool.ToolInvocation("id", "test_tool", Map.of());
        assertThrows(IllegalArgumentException.class, () -> LlmDecision.toolCalls(List.of(call, call), null));
        var decision = LlmDecision.toolCalls(List.of(call), null);
        assertThrows(IllegalArgumentException.class, () -> new AgentStep("step-1", 1, decision, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AgentStep("step-1", 1, decision,
                List.of(new ToolSuccess<>("wrong-id", "test_tool", "ok"))));
    }

    @Test
    void writesEachResultBeforeStartingNextTool() {
        var calls = List.of(
                new agent_backend.tool.ToolInvocation("first", "test_tool", Map.of()),
                new agent_backend.tool.ToolInvocation("second", "test_tool", Map.of()));
        when(llm.parseDecision(any())).thenReturn(LlmDecision.toolCalls(calls, null), LlmDecision.finalAnswer("done"));
        org.mockito.Mockito.doReturn(List.of("inv-1", "inv-2")).when(invocations).createBatch(any(), any(), any());
        when(tools.callTool(any(), any(), any())).thenAnswer(invocation ->
                new ToolSuccess<>(invocation.getArgument(0), "test_tool", "ok"));
        var state = runner(2).run("task");
        var order = org.mockito.Mockito.inOrder(tasks, steps, llm, invocations, tools);
        order.verify(tasks).create(state.getTaskId(), "task");
        order.verify(tasks).start(new TaskLease(state.getTaskId(), "test-worker"), 60000);
        order.verify(steps).create(new TaskLease(state.getTaskId(), "test-worker"), state.getSteps().getFirst().getStepId(), 1);
        order.verify(llm).request(any());
        order.verify(steps).saveResponse(any(), any(), any());
        order.verify(llm).parseDecision(any());
        order.verify(invocations).createBatch(any(), any(), org.mockito.ArgumentMatchers.eq(calls));
        order.verify(invocations).start(any(), org.mockito.ArgumentMatchers.eq("inv-1"));
        order.verify(tools).callTool("first", "test_tool", Map.of());
        order.verify(invocations).finish(any(), org.mockito.ArgumentMatchers.eq("inv-1"), any());
        order.verify(invocations).start(any(), org.mockito.ArgumentMatchers.eq("inv-2"));
        order.verify(tools).callTool("second", "test_tool", Map.of());
        order.verify(invocations).finish(any(), org.mockito.ArgumentMatchers.eq("inv-2"), any());
        order.verify(steps).complete(any(), org.mockito.ArgumentMatchers.eq(state.getSteps().getFirst().getStepId()));
        order.verify(llm).request(any());
        order.verify(steps).complete(any(), any());
        order.verify(tasks).complete(new TaskLease(state.getTaskId(), "test-worker"), "done");
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
    }

    @Test
    void resultSaveFailureStopsRemainingCallsAndAttemptsFailureCleanup() {
        var calls = List.of(
                new agent_backend.tool.ToolInvocation("first", "test_tool", Map.of()),
                new agent_backend.tool.ToolInvocation("second", "test_tool", Map.of()));
        when(llm.parseDecision(any())).thenReturn(LlmDecision.toolCalls(calls, null));
        org.mockito.Mockito.doReturn(List.of("inv-1", "inv-2")).when(invocations).createBatch(any(), any(), any());
        when(tools.callTool("first", "test_tool", Map.of()))
                .thenAnswer(invocation -> new ToolSuccess<>("first", "test_tool", "ok"));
        org.mockito.Mockito.doThrow(new IllegalStateException("database write failed"))
                .when(invocations).finish(any(), org.mockito.ArgumentMatchers.eq("inv-1"), any());
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verify(tools, org.mockito.Mockito.never()).callTool("second", "test_tool", Map.of());
        org.mockito.Mockito.verify(invocations).abortUnfinished(any(), any(), org.mockito.ArgumentMatchers.contains("database write failed"));
        org.mockito.Mockito.verify(steps).fail(any(), any(), any());
        org.mockito.Mockito.verify(tasks).fail(new TaskLease(state.getTaskId(), "test-worker"), state.getFailureReason());
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.times(1)).request(any());
    }

    @Test
    void batchSaveFailurePreventsAllToolSideEffects() {
        when(llm.parseDecision(any())).thenReturn(step().getDecision());
        org.mockito.Mockito.doThrow(new IllegalStateException("batch save failed")).when(invocations).createBatch(any(), any(), any());
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verifyNoInteractions(tools);
    }

    @Test
    void rawResponseSaveFailurePreventsParsingAndExecution() {
        org.mockito.Mockito.doThrow(new IllegalStateException("response save failed"))
                .when(steps).saveResponse(any(), any(), any());
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.never()).parseDecision(any());
        org.mockito.Mockito.verifyNoInteractions(tools);
    }

    @Test
    void unavailableFailureStorageIsReportedAndOtherCleanupStillAttempted() {
        when(llm.request(any())).thenThrow(new IllegalStateException("request failed"));
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup unavailable"))
                .when(invocations).abortUnfinished(any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("task write unavailable"))
                .when(tasks).fail(any(), any());
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertTrue(state.getFailureReason().contains("request failed"));
        assertTrue(state.getFailureReason().contains("Failure persistence incomplete"));
        org.mockito.Mockito.verify(steps).fail(any(), any(), any());
        org.mockito.Mockito.verify(tasks).fail(any(), any());
    }

    @Test
    void taskCreationFailureNeverRequestsModel() {
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(tasks).create(any(), any());
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verifyNoInteractions(llm, tools, steps, invocations);
    }

    @Test
    void existingTaskUsesPersistedIdAndIsNotCreatedAgain() {
        String taskId = java.util.UUID.randomUUID().toString();
        when(tasks.find(taskId)).thenReturn(java.util.Optional.of(new agent_backend.persistence.model.TaskRecord(
                taskId, "stored input", agent_backend.persistence.model.TaskStatus.PENDING,
                null, null, null, null, null, null, null, null, null)));
        when(llm.parseDecision(any())).thenReturn(LlmDecision.finalAnswer("done"));
        var state = runner(2).runExisting(taskId);
        assertEquals(taskId, state.getTaskId());
        assertEquals("stored input", state.getTask());
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).create(any(), any());
        org.mockito.Mockito.verify(tasks).complete(new TaskLease(taskId, "test-worker"), "done");
    }

    @Test
    void rejectedStartDoesNotFailAnotherExecutorsTask() {
        String taskId = java.util.UUID.randomUUID().toString();
        when(tasks.find(taskId)).thenReturn(java.util.Optional.of(new agent_backend.persistence.model.TaskRecord(
                taskId, "stored input", agent_backend.persistence.model.TaskStatus.RUNNING,
                null, null, null, null, null, null, null, null, null)));
        org.mockito.Mockito.doThrow(new IllegalStateException("already started")).when(leases).acquire(taskId);
        var state = runner(2).runExisting(taskId);
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verify(tasks, org.mockito.Mockito.never()).fail(any(), any());
        org.mockito.Mockito.verifyNoInteractions(llm, tools, steps, invocations);
    }

    @Test
    void leaseLossDuringToolExecutionStopsTheBatchAndClosesHeartbeat() {
        var lease = new TaskLease("task-id", "worker-id");
        LeaseSession session = mock(LeaseSession.class);
        when(session.lease()).thenReturn(lease);
        org.mockito.Mockito.doReturn(session).when(leases).acquire(any());
        when(llm.parseDecision(any())).thenReturn(LlmDecision.toolCalls(List.of(
                new agent_backend.tool.ToolInvocation("first", "test_tool", Map.of()),
                new agent_backend.tool.ToolInvocation("second", "test_tool", Map.of())), null));
        when(tools.callTool("first", "test_tool", Map.of())).thenAnswer(invocation -> {
            org.mockito.Mockito.doThrow(new agent_backend.persistence.LeaseLostException(lease.taskId()))
                    .when(session).check();
            return new ToolSuccess<>("first", "test_tool", "late result");
        });
        var state = runner(2).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        org.mockito.Mockito.verify(tools, org.mockito.Mockito.never()).callTool("second", "test_tool", Map.of());
        org.mockito.Mockito.verify(invocations, org.mockito.Mockito.never()).finish(any(), any(), any());
        org.mockito.Mockito.verify(session).close();
    }
}

package agent_backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import agent_backend.persistence.model.TaskStatus;

// MockMvc 验证 SSE 响应和异步生命周期；模拟存储，不依赖 MySQL 或真实模型请求。
class TaskEventTests {
    private final String id = UUID.randomUUID().toString();
    private final TaskService tasks = mock(TaskService.class);
    private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    private TaskSubscriptionService subscriptions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        // 测试中直接执行发送任务并手动 poll，使事件时序可控；生产使用独立发送线程池。
        subscriptions = new TaskSubscriptionService(tasks, timer, Runnable::run, 1000, 15000, 300000);
        mvc = MockMvcBuilders.standaloneSetup(new TaskEventController(subscriptions))
                .setControllerAdvice(new TaskApiExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() { subscriptions.stop(); }

    private TaskSnapshot snapshot(TaskStatus status) {
        return new TaskSnapshot(id, "test", status, status == TaskStatus.COMPLETED ? "done" : null,
                status == TaskStatus.FAILED ? "failed" : null, null, null, null, List.of());
    }

    @Test
    void initialSnapshotChangesAndTerminalClose() throws Exception {
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var result = mvc.perform(get("/tasks/{id}/events", id))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(header().string("Cache-Control", "no-cache")).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("event:snapshot"));
        String initial = result.getResponse().getContentAsString();
        subscriptions.poll();
        assertEquals(initial, result.getResponse().getContentAsString());
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.COMPLETED));
        subscriptions.poll();
        assertTrue(result.getResponse().getContentAsString().contains("\"finalAnswer\":\"done\""));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
    }

    @Test
    void pollsOnceForMultipleSubscribersAndReconnectGetsCurrentSnapshot() throws Exception {
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.PENDING));
        mvc.perform(get("/tasks/{id}/events", id));
        mvc.perform(get("/tasks/{id}/events", id));
        clearInvocations(tasks);
        subscriptions.poll();
        verify(tasks, times(1)).getSnapshot(id);
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var reconnected = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        assertTrue(reconnected.getResponse().getContentAsString().contains("RUNNING"));
    }

    @Test
    void alreadyFailedTaskIsSentAndClosedImmediately() throws Exception {
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.FAILED));
        var result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("\"failureReason\":\"failed\""));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
    }

    @Test
    void invalidAndMissingTasksFailBeforeStreaming() throws Exception {
        mvc.perform(get("/tasks/not-a-uuid/events")).andExpect(status().isBadRequest());
        when(tasks.getSnapshot(id)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/tasks/{id}/events", id)).andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());
    }

    @Test
    void storageFailureBeforeSubscriptionReturnsJson503() throws Exception {
        when(tasks.getSnapshot(id)).thenThrow(
                new org.springframework.dao.DataAccessResourceFailureException("unavailable"));
        mvc.perform(get("/tasks/{id}/events", id).accept("text/event-stream"))
                .andExpect(status().isServiceUnavailable()).andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("TASK_STORAGE_UNAVAILABLE"));
    }

    @Test
    void disconnectAndTimeoutRemoveSubscriptions() throws Exception {
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        var context = (org.springframework.mock.web.MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onError(new jakarta.servlet.AsyncEvent(context, new java.io.IOException("disconnected")));
        }
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
        result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        context = (org.springframework.mock.web.MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) {
            listener.onTimeout(new jakarta.servlet.AsyncEvent(context));
        }
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
    }

    @Test
    void failedSnapshotReadClosesAndRemovesSubscription() throws Exception {
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        when(tasks.getSnapshot(id)).thenThrow(new IllegalStateException("database unavailable"));
        subscriptions.poll();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
    }

    @Test
    void fullSendQueueClosesConnectionWithoutRunningOnPollThread() throws Exception {
        subscriptions.stop();
        subscriptions = new TaskSubscriptionService(tasks, timer,
                command -> { throw new RejectedExecutionException(); }, 1000, 15000, 300000);
        mvc = MockMvcBuilders.standaloneSetup(new TaskEventController(subscriptions)).build();
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        subscriptions.poll();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        clearInvocations(tasks);
        subscriptions.poll();
        verifyNoInteractions(tasks);
    }

    @Test
    void idleStreamSendsHeartbeatWithoutRepeatingSnapshot() throws Exception {
        subscriptions.stop();
        subscriptions = new TaskSubscriptionService(tasks, timer, Runnable::run, 1000, 1, 300000);
        mvc = MockMvcBuilders.standaloneSetup(new TaskEventController(subscriptions)).build();
        when(tasks.getSnapshot(id)).thenReturn(snapshot(TaskStatus.RUNNING));
        var result = mvc.perform(get("/tasks/{id}/events", id)).andReturn();
        Thread.sleep(5);
        subscriptions.poll();
        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains(":heartbeat"));
        assertEquals(1, content.split("event:snapshot", -1).length - 1);
    }

    @Test
    void snapshotIgnoresLeaseChangesButIncludesStepAndToolResults() {
        var store = mock(agent_backend.persistence.TaskStore.class);
        var steps = mock(agent_backend.persistence.StepStore.class);
        var calls = mock(agent_backend.persistence.ToolInvocationStore.class);
        var service = new TaskService(store, steps, calls, tools.jackson.databind.json.JsonMapper.builder().build());
        var now = java.time.LocalDateTime.of(2026, 10, 10, 0, 0);
        when(store.find(id)).thenReturn(java.util.Optional.of(new agent_backend.persistence.model.TaskRecord(
                id, "test", TaskStatus.RUNNING, null, null, now, now, null, now, "owner", now, now.plusMinutes(1))));
        var before = service.getSnapshot(id);
        when(store.find(id)).thenReturn(java.util.Optional.of(new agent_backend.persistence.model.TaskRecord(
                id, "test", TaskStatus.RUNNING, null, null, now, now, null, now.plusSeconds(10),
                "owner", now.plusSeconds(10), now.plusMinutes(2))));
        assertEquals(before, service.getSnapshot(id));
        when(steps.findByTask(id)).thenReturn(List.of(new agent_backend.persistence.model.StepRecord(
                "step", id, 1, agent_backend.persistence.model.StepStatus.RUNNING, null, null, now, null, now)));
        when(calls.findByStep("step")).thenReturn(List.of(new agent_backend.persistence.model.ToolInvocationRecord(
                "invocation", "step", "protocol-id", 1, "test", "{}",
                agent_backend.persistence.model.ToolCallStatus.SUCCEEDED, "{\"data\":42}", null,
                now, now, now, now)));
        var after = service.getSnapshot(id);
        assertNotEquals(before, after);
        assertEquals(42, after.steps().getFirst().calls().getFirst().result().get("data").asInt());
    }
}

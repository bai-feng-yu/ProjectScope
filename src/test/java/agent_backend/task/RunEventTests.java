package agent_backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import agent_backend.TestIdentity;
import agent_backend.persistence.model.TaskStatus;
import agent_backend.security.TaskIdentity;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RunEventTests {
    final String id = UUID.randomUUID().toString();
    final RunEventQuery query = mock(RunEventQuery.class);
    final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    TaskIdentity identity;
    RunEventSubscriptionService subscriptions;
    MockMvc mvc;

    @BeforeEach void setup() { identity = TestIdentity.login("alice"); configure(Runnable::run); }
    void configure(Executor executor) {
        subscriptions = new RunEventSubscriptionService(query, timer, executor, 1000, 15000, 300000);
        mvc = MockMvcBuilders.standaloneSetup(new RunEventController(subscriptions))
                .setControllerAdvice(new TaskApiExceptionHandler()).build();
    }
    @AfterEach void cleanup() { subscriptions.stop(); TestIdentity.clear(); }
    RunEventQuery.Initial initial(long seq, TaskStatus status) {
        return new RunEventQuery.Initial(seq, new TaskSnapshot(id, "input", status,
                null, null, null, null, null, List.of()));
    }
    RunEventQuery.Page page(long high, boolean terminal, long... seqs) {
        return new RunEventQuery.Page(high, terminal, Arrays.stream(seqs).mapToObj(seq ->
                new RunEventQuery.Event(id, seq, "run.changed", 1, null, null, null, null, Instant.EPOCH)).toList());
    }

    @Test void firstConnectionGetsSnapshotThenOnlyEventsAfterItsWatermark() throws Exception {
        when(query.initial(id, identity)).thenReturn(initial(5, TaskStatus.RUNNING));
        var result = mvc.perform(get("/runs/{id}/events", id)).andExpect(status().isOk()).andReturn();
        assertTrue(result.getResponse().getContentAsString().contains("id:5\nevent:snapshot"));
        when(query.after(id, identity, 5)).thenReturn(page(6, true, 6));
        TestIdentity.clear(); // Polls must use the captured identity, not a request thread-local.
        subscriptions.poll();
        assertTrue(result.getResponse().getContentAsString().contains("id:6\nevent:run.changed"));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        clearInvocations(query);
        subscriptions.poll();
        verifyNoInteractions(query);
    }

    @Test void reconnectReplaysWithoutSnapshotAndHeaderOverridesQuery() throws Exception {
        when(query.after(id, identity, 5)).thenReturn(page(7, true, 6, 7));
        var result = mvc.perform(get("/runs/{id}/events?after=0", id).header("Last-Event-ID", "5"))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("snapshot"));
        assertTrue(body.indexOf("id:6\n") < body.indexOf("id:7\n"));
        verify(query, never()).initial(anyString(), any());
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test void terminalRunIsDrainedAcrossPagesBeforeClosing() throws Exception {
        when(query.after(id, identity, 0)).thenReturn(page(3, true, 1));
        when(query.after(id, identity, 1)).thenReturn(page(3, true, 2));
        when(query.after(id, identity, 2)).thenReturn(page(3, true, 3));
        var result = mvc.perform(get("/runs/{id}/events?after=0", id)).andReturn();
        subscriptions.poll();
        subscriptions.poll();
        String body = result.getResponse().getContentAsString();
        for (int i = 1; i <= 3; i++) assertTrue(body.contains("id:" + i + "\n"));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test void caughtUpTerminalReconnectReturns204() throws Exception {
        when(query.after(id, identity, 7)).thenReturn(page(7, true));
        mvc.perform(get("/runs/{id}/events", id).header("Last-Event-ID", "7"))
                .andExpect(status().isNoContent()).andExpect(request().asyncNotStarted());
    }

    @Test void rejectsMalformedAndAheadCursors() throws Exception {
        for (String bad : List.of("-1", " 1", "1.2", "9223372036854775808", "abc", ""))
            mvc.perform(get("/runs/{id}/events", id).header("Last-Event-ID", bad)).andExpect(status().isBadRequest());
        verifyNoInteractions(query);
        when(query.after(id, identity, 99)).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST));
        mvc.perform(get("/runs/{id}/events?after=99", id)).andExpect(status().isBadRequest());
    }

    @Test void busySenderDoesNotAdvanceOrDropCursor() throws Exception {
        List<Runnable> queue = new ArrayList<>();
        configure(queue::add);
        when(query.after(id, identity, 0)).thenReturn(page(0, false));
        var result = mvc.perform(get("/runs/{id}/events?after=0", id)).andReturn();
        when(query.after(id, identity, 0)).thenReturn(page(2, true, 1, 2));
        subscriptions.poll(); subscriptions.poll(); subscriptions.poll();
        assertEquals(1, queue.size());
        queue.removeFirst().run();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("id:1\n")); assertTrue(body.contains("id:2\n"));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test void expiredIdentityClosesBeforeDatabaseRead() throws Exception {
        when(query.initial(id, identity)).thenReturn(initial(1, TaskStatus.RUNNING));
        var result = mvc.perform(get("/runs/{id}/events", id)).andReturn();
        clearInvocations(query);
        Instant expired = identity.expiresAt().plusSeconds(1);
        try (var clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(expired);
            subscriptions.poll();
        }
        verifyNoInteractions(query);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test void overloadedSenderClosesAndReconnectCanReplay() throws Exception {
        configure(command -> { throw new RejectedExecutionException(); });
        when(query.initial(id, identity)).thenReturn(initial(1, TaskStatus.RUNNING));
        var result = mvc.perform(get("/runs/{id}/events", id)).andReturn();
        subscriptions.poll();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        configure(Runnable::run);
        when(query.after(id, identity, 1)).thenReturn(page(2, true, 2));
        var resumed = mvc.perform(get("/runs/{id}/events?after=1", id)).andReturn();
        assertTrue(resumed.getResponse().getContentAsString().contains("id:2\n"));
    }

    @Test void initialStorageFailureIs503BeforeStreamStarts() throws Exception {
        when(query.initial(id, identity)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        mvc.perform(get("/runs/{id}/events", id)).andExpect(status().isServiceUnavailable())
                .andExpect(request().asyncNotStarted());
    }

    @Test void missingSequenceClosesWithoutSendingLaterEvents() throws Exception {
        when(query.after(id, identity, 1)).thenReturn(page(3, false, 3));
        var result = mvc.perform(get("/runs/{id}/events?after=1", id)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("id:3"));
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}

package agent_backend;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import agent_backend.persistence.TaskStore;
import agent_backend.task.TaskDispatcher;
import agent_backend.task.PendingTaskScheduler;
import org.junit.jupiter.api.Test;

class PendingTaskSchedulerTests {
    private final TaskStore tasks = mock(TaskStore.class);
    private final TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    private final PendingTaskScheduler scheduler = new PendingTaskScheduler(tasks, dispatcher);

    @Test
    void noCapacityDoesNotReadOrClaimAnyTasks() {
        scheduler.poll();
        verifyNoInteractions(tasks);
        verify(dispatcher, never()).tryDispatch(any());
    }

    @Test
    void scansOnlyAvailableCapacityAndLeavesClaimsToWorker() {
        when(dispatcher.availableCapacity()).thenReturn(2);
        when(tasks.findPending(2)).thenReturn(List.of("first", "second"));
        scheduler.poll();
        verify(tasks).findPending(2);
        verify(dispatcher).tryDispatch("first");
        verify(dispatcher).tryDispatch("second");
        verify(tasks, never()).start(any());
        verify(tasks, never()).fail(any(), any());
    }

    @Test
    void rejectedCandidateRemainsEligibleOnNextPoll() {
        when(dispatcher.availableCapacity()).thenReturn(1);
        when(tasks.findPending(1)).thenReturn(List.of("task"));
        when(dispatcher.tryDispatch("task")).thenReturn(false, true);
        scheduler.poll();
        scheduler.poll();
        verify(dispatcher, times(2)).tryDispatch("task");
        verify(tasks, never()).fail(any(), any());
    }

    @Test
    void databaseOutageDoesNotStopFuturePolling() {
        when(dispatcher.availableCapacity()).thenReturn(1);
        when(tasks.findPending(1)).thenThrow(new IllegalStateException("offline")).thenReturn(List.of("task"));
        assertDoesNotThrow(scheduler::poll);
        scheduler.poll();
        verify(dispatcher).tryDispatch("task");
    }
}

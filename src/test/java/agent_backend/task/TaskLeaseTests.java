package agent_backend.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.LeaseLostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskLeaseTests {
    private final TaskStore tasks = mock(TaskStore.class);
    private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final TaskLeaseManager manager = new TaskLeaseManager(tasks, executor, 60000, 10000);

    @BeforeEach
    void setup() {
        when(executor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(invocation -> future);
    }

    private Runnable heartbeat() {
        var captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(captor.capture(), eq(10000L), eq(10000L), eq(TimeUnit.MILLISECONDS));
        return captor.getValue();
    }

    @Test
    void claimsBeforeSchedulingAndRenewsUntilClosed() {
        LeaseSession session = manager.acquire("task");
        var order = inOrder(tasks, executor);
        order.verify(tasks).start(session.lease(), 60000);
        order.verify(executor).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        when(tasks.renew(session.lease(), 60000)).thenReturn(true);
        heartbeat().run();
        session.check();
        session.close();
        verify(future).cancel(false);
        assertThrows(LeaseLostException.class, session::check);
    }

    @Test
    void unsuccessfulRenewalFencesExecutionAndCancelsHeartbeat() {
        LeaseSession session = manager.acquire("task");
        heartbeat().run();
        assertThrows(LeaseLostException.class, session::check);
        verify(future).cancel(false);
    }

    @Test
    void databaseOutageAlsoStopsFurtherProgress() {
        LeaseSession session = manager.acquire("task");
        when(tasks.renew(any(), anyLong())).thenThrow(new IllegalStateException("offline"));
        assertDoesNotThrow(() -> heartbeat().run());
        assertThrows(LeaseLostException.class, session::check);
    }

    @Test
    void failedClaimDoesNotScheduleHeartbeatOrChangeTaskFailureState() {
        doThrow(new IllegalStateException("already claimed")).when(tasks).start(any(), anyLong());
        assertThrows(IllegalStateException.class, () -> manager.acquire("task"));
        verifyNoInteractions(executor);
        verify(tasks, never()).fail(any(), any());
    }

    @Test
    void unavailableHeartbeatExecutorClosesClaimedTask() {
        doThrow(new RejectedExecutionException("shutdown")).when(executor)
                .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        assertThrows(RejectedExecutionException.class, () -> manager.acquire("task"));
        verify(tasks).fail(any(), eq("Could not start task heartbeat"));
    }

    @Test
    void recoveryContinuesAfterOneTaskFailureAndRetriesAfterScanFailure() {
        var recovery = new TaskRecoveryService(tasks, 10);
        when(tasks.findExpired(10)).thenThrow(new IllegalStateException("offline"))
                .thenReturn(List.of("first", "second"));
        when(tasks.recoverExpired("first")).thenThrow(new IllegalStateException("write failed"));
        assertDoesNotThrow(recovery::recover);
        assertDoesNotThrow(recovery::recover);
        verify(tasks).recoverExpired("second");
    }
}

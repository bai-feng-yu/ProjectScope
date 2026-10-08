package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import agent_backend.task.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class LocalTaskDispatcherTests {
    @Test
    void boundsCapacityAndSuppressesDuplicateLocalCandidates() throws Exception {
        var executor = new TaskExecutorConfig().agentTaskExecutor(2);
        executor.initialize();
        TaskWorker worker = mock(TaskWorker.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(worker).execute(any());
        var dispatcher = new LocalTaskDispatcher(executor, worker);
        try {
            assertTrue(dispatcher.tryDispatch("first"));
            assertFalse(dispatcher.tryDispatch("first"));
            assertEquals(1, dispatcher.availableCapacity());
            assertTrue(dispatcher.tryDispatch("second"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(0, dispatcher.availableCapacity());
            assertFalse(dispatcher.tryDispatch("third"));
            assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            executor.shutdown();
        }
        assertEquals(2, dispatcher.availableCapacity());
        verify(worker, times(1)).execute("first");
        verify(worker, times(1)).execute("second");
        verify(worker, never()).execute("third");
    }

    @Test
    void rejectionReleasesCapacityAndAllowsTheSameCandidateToBeRetried() {
        var executor = mock(ThreadPoolTaskExecutor.class);
        when(executor.getMaxPoolSize()).thenReturn(1);
        doThrow(new RejectedExecutionException("shutting down")).when(executor).execute(any(Runnable.class));
        TaskWorker worker = mock(TaskWorker.class);
        var dispatcher = new LocalTaskDispatcher(executor, worker);
        assertFalse(dispatcher.tryDispatch("task"));
        assertEquals(1, dispatcher.availableCapacity());
        assertFalse(dispatcher.tryDispatch("task"));
        verify(executor, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(worker);
    }

    @Test
    void workerFailureDoesNotLeakExecutionCapacity() {
        var executor = new TaskExecutorConfig().agentTaskExecutor(1);
        executor.initialize();
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.runExisting("task")).thenThrow(new IllegalStateException("database unavailable"));
        var dispatcher = new LocalTaskDispatcher(executor, new TaskWorker(runner));
        try {
            assertTrue(dispatcher.tryDispatch("task"));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (executor.getThreadPoolExecutor().getCompletedTaskCount() < 1) Thread.sleep(5);
            });
            assertEquals(1, dispatcher.availableCapacity());
        } finally {
            executor.shutdown();
        }
    }
}

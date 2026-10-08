package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import agent_backend.task.LocalTaskDispatcher;
import agent_backend.task.TaskExecutorConfig;
import org.junit.jupiter.api.Test;

class LocalTaskDispatcherTests {
    @Test
    void dispatchReturnsWhileWorkerIsBlockedAndCapacityNeverFallsBackToCaller() throws Exception {
        var executor = new TaskExecutorConfig().agentTaskExecutor(1, 0);
        executor.initialize();
        AgentRunner runner = mock(AgentRunner.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var workerThread = new AtomicReference<Thread>();
        when(runner.runExisting(anyString())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread());
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            } finally {
                finished.countDown();
            }
        });
        try {
            var dispatcher = new LocalTaskDispatcher(executor, runner);
            dispatcher.dispatch("first");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), workerThread.get());
            assertEquals(1, finished.getCount());
            assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch("second"));
            verify(runner, never()).runExisting("second");
        } finally {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            executor.shutdown();
        }
    }
}

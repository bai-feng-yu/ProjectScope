package agent_backend.task;

import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Bounded isolation for read-only adapter lookups. Timeout never authorizes an execution retry. */
@Component
public class ToolRecoveryExecutor {
    private final ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("tool-recovery-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    public <T> T lookup(Callable<T> call) throws Exception {
        var future = executor.submit(call);
        try { return future.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
        finally { if (!future.isDone()) future.cancel(true); }
    }
    @PreDestroy public void stop() { executor.shutdownNow(); }
}

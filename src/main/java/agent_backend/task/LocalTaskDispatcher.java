package agent_backend.task;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class LocalTaskDispatcher implements TaskDispatcher {
    private final ThreadPoolTaskExecutor executor;
    private final TaskWorker worker;
    private final Semaphore slots;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public LocalTaskDispatcher(@Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor, TaskWorker worker) {
        this.executor = executor;
        this.worker = worker;
        this.slots = new Semaphore(executor.getMaxPoolSize());
    }

    @Override
    public int availableCapacity() { return slots.availablePermits(); }

    @Override
    public boolean tryDispatch(String taskId) {
        if (!slots.tryAcquire()) return false;
        if (!inFlight.add(taskId)) {
            slots.release();
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    worker.execute(taskId);
                } finally {
                    release(taskId);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            // The worker never started, so the database record remains PENDING.
            release(taskId);
            return false;
        } catch (RuntimeException exception) {
            release(taskId);
            throw exception;
        }
    }

    private void release(String taskId) {
        inFlight.remove(taskId);
        slots.release();
    }
}

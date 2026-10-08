package agent_backend.task;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import agent_backend.persistence.TaskLease;
import agent_backend.persistence.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class TaskLeaseManager {
    private static final Logger log = LoggerFactory.getLogger(TaskLeaseManager.class);
    private final TaskStore tasks;
    private final ScheduledExecutorService scheduler;
    private final long durationMs;
    private final long heartbeatMs;

    public TaskLeaseManager(TaskStore tasks,
            @Qualifier("leaseHeartbeatExecutor") ScheduledExecutorService scheduler,
            @Value("${agent.lease.duration-ms:60000}") long durationMs,
            @Value("${agent.lease.heartbeat-interval-ms:10000}") long heartbeatMs) {
        Assert.isTrue(heartbeatMs > 0 && durationMs > heartbeatMs, "Lease duration must exceed heartbeat interval");
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.durationMs = durationMs;
        this.heartbeatMs = heartbeatMs;
    }

    public LeaseSession acquire(String taskId) {
        TaskLease lease = new TaskLease(taskId, UUID.randomUUID().toString());
        tasks.start(lease, durationMs);
        LeaseSession session = new LeaseSession(lease);
        try {
            session.heartbeat(scheduler.scheduleWithFixedDelay(() -> {
                try {
                    session.check();
                    if (!tasks.renew(lease, durationMs)) session.close();
                } catch (Exception exception) {
                    session.close();
                    log.warn("Heartbeat lost for task {}", taskId, exception);
                }
            }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS));
            return session;
        } catch (RuntimeException exception) {
            session.close();
            try {
                tasks.fail(lease, "Could not start task heartbeat");
            } catch (RuntimeException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }
}

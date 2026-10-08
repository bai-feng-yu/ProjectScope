package agent_backend.task;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import agent_backend.persistence.TaskLease;
import agent_backend.persistence.LeaseLostException;

/** Local loss signal complements the database's authoritative lease check. */
public class LeaseSession implements AutoCloseable {
    private final TaskLease lease;
    private final AtomicBoolean lost = new AtomicBoolean();
    private volatile ScheduledFuture<?> heartbeat;

    LeaseSession(TaskLease lease) { this.lease = lease; }
    public TaskLease lease() { return lease; }
    void heartbeat(ScheduledFuture<?> heartbeat) { this.heartbeat = heartbeat; }

    public void check() {
        if (lost.get()) throw new LeaseLostException(lease.taskId());
    }

    @Override
    public void close() {
        lost.set(true);
        if (heartbeat != null) heartbeat.cancel(false);
    }
}

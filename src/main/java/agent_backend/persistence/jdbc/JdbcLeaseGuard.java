package agent_backend.persistence.jdbc;

import agent_backend.persistence.TaskLease;
import agent_backend.persistence.LeaseLostException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

@Component
public class JdbcLeaseGuard {
    private final JdbcTemplate jdbc;

    public JdbcLeaseGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Always lock the parent task first, in the same short transaction as the following writes. */
    public void lock(TaskLease lease) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(), "Lease guard requires a transaction");
        var rows = jdbc.queryForList("""
                SELECT task_id FROM agent_task
                WHERE task_id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > UTC_TIMESTAMP(6)
                FOR UPDATE
                """, String.class, lease.taskId(), lease.workerId());
        if (rows.isEmpty()) throw new LeaseLostException(lease.taskId());
    }
}

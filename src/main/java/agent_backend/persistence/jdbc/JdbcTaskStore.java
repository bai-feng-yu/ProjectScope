package agent_backend.persistence.jdbc;

import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.TaskLease;
import org.springframework.transaction.annotation.Transactional;
import agent_backend.persistence.model.TaskRecord;

@Repository
public class JdbcTaskStore implements TaskStore {
    private final JdbcTemplate jdbc;

    private final JdbcLeaseGuard leases;
    public JdbcTaskStore(JdbcTemplate jdbc, JdbcLeaseGuard leases) {
        this.jdbc = jdbc;
        this.leases = leases;
    }

    @Override
    public void create(String taskId, String input) {
        Assert.hasText(input, "task must not be blank");
        jdbc.update("INSERT INTO agent_task (task_id, task_input) VALUES (?, ?)", taskId, input);
    }

    @Override
    public void start(TaskLease lease, long durationMs) {
        Assert.isTrue(durationMs > 0, "Lease duration must be positive");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'RUNNING', worker_id = ?,
                    started_at = UTC_TIMESTAMP(6), heartbeat_at = UTC_TIMESTAMP(6),
                    lease_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                WHERE task_id = ? AND status = 'PENDING'
                """, lease.workerId(), Math.multiplyExact(durationMs, 1000), lease.taskId()), "start task", lease.taskId());
    }

    @Override
    public boolean renew(TaskLease lease, long durationMs) {
        Assert.isTrue(durationMs > 0, "Lease duration must be positive");
        return jdbc.update("""
                UPDATE agent_task SET heartbeat_at = UTC_TIMESTAMP(6),
                    lease_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                WHERE task_id = ? AND worker_id = ? AND status = 'RUNNING' AND lease_until > UTC_TIMESTAMP(6)
                """, Math.multiplyExact(durationMs, 1000), lease.taskId(), lease.workerId()) == 1;
    }

    @Override
    @Transactional
    public void complete(TaskLease lease, String answer) {
        Assert.hasText(answer, "final answer must not be blank");
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'COMPLETED', final_answer = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'RUNNING'
                  AND NOT EXISTS (SELECT 1 FROM agent_step WHERE task_id = ? AND status = 'RUNNING')
                """, answer, lease.taskId(), lease.taskId()), "complete task", lease.taskId());
    }

    @Override
    @Transactional
    public void fail(TaskLease lease, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        leases.lock(lease);
        closeUnfinishedChildren(lease.taskId(), reason);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'RUNNING'
                """, reason, lease.taskId()), "fail task", lease.taskId());
    }

    @Override
    public void failPending(String taskId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'PENDING'
                """, reason, taskId), "fail pending task", taskId);
    }

    @Override
    public List<String> findExpired(int limit) {
        Assert.isTrue(limit > 0, "limit must be positive");
        return jdbc.queryForList("""
                SELECT task_id FROM agent_task WHERE status = 'RUNNING'
                    AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6))
                ORDER BY lease_until, task_id LIMIT ?
                """, String.class, limit);
    }

    @Override
    @Transactional
    public boolean recoverExpired(String taskId) {
        // Serialize against heartbeat and every writer via the same parent-row lock.
        var expired = jdbc.queryForList("""
                SELECT task_id FROM agent_task WHERE task_id = ? AND status = 'RUNNING'
                    AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6)) FOR UPDATE
                """, String.class, taskId);
        if (expired.isEmpty()) return false;
        String reason = "Task execution interrupted: worker lease expired";
        closeUnfinishedChildren(taskId, reason);
        jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'RUNNING'
                """, reason, taskId);
        return true;
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        return jdbc.query("SELECT * FROM agent_task WHERE task_id = ?",
                DataClassRowMapper.newInstance(TaskRecord.class), taskId).stream().findFirst();
    }

    @Override
    public List<String> findPending(int limit) {
        Assert.isTrue(limit > 0, "limit must be positive");
        return jdbc.queryForList("""
                SELECT task_id FROM agent_task WHERE status = 'PENDING'
                ORDER BY created_at, task_id LIMIT ?
                """, String.class, limit);
    }
    private void closeUnfinishedChildren(String taskId, String reason) {
        jdbc.update("""
                UPDATE agent_tool_call c JOIN agent_step s ON s.step_id = c.step_id
                SET c.status = CASE WHEN c.status = 'RUNNING' THEN 'UNKNOWN' ELSE 'FAILED' END,
                    c.failure_reason = ?, c.finished_at = UTC_TIMESTAMP(6)
                WHERE s.task_id = ? AND c.status IN ('PENDING', 'RUNNING')
                """, reason, taskId);
        jdbc.update("""
                UPDATE agent_step SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'RUNNING'
                """, reason, taskId);
    }
}

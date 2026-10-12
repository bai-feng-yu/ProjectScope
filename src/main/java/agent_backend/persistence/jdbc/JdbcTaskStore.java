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
    // Old Task DTO lease fields are projected from the latest Attempt, never duplicated on the Run.
    private static final String RUN_SELECT = """
            SELECT r.*, a.worker_id, a.heartbeat_at, a.lease_until
            FROM agent_task r LEFT JOIN run_attempt a ON a.run_id = r.task_id AND a.fence_token = r.fence_token
            """;
    private final JdbcTemplate jdbc;

    private final JdbcLeaseGuard leases;
    public JdbcTaskStore(JdbcTemplate jdbc, JdbcLeaseGuard leases) {
        this.jdbc = jdbc;
        this.leases = leases;
    }

    @Override
    @Transactional
    public void create(String taskId, String input) {
        Assert.hasText(input, "task must not be blank");
        jdbc.update("INSERT INTO agent_task (task_id, task_input) VALUES (?, ?)", taskId, input);
        JdbcRunEvents.append(jdbc, taskId, "run.created", null, null, null, "PENDING");
    }

    @Override
    @Transactional
    public void createOwned(String taskId, String input, String userId) {
        Assert.hasText(input, "task must not be blank");
        Assert.hasText(userId, "userId must not be blank");
        jdbc.update("INSERT INTO agent_task (task_id, task_input, user_id) VALUES (?, ?, ?)", taskId, input, userId);
        JdbcRunEvents.append(jdbc, taskId, "run.created", null, null, null, "PENDING");
    }

    @Override
    public Optional<TaskRecord> findOwned(String taskId, String userId) {
        Assert.hasText(userId, "userId must not be blank");
        return jdbc.query(RUN_SELECT + " WHERE r.task_id = ? AND r.user_id = ?",
                DataClassRowMapper.newInstance(TaskRecord.class), taskId, userId).stream().findFirst();
    }

    @Override
    @Transactional
    public String createOwnedOnce(String input, String userId, String key) {
        var keys = agent_backend.task.RequestKeys.class;
        String keyHash = agent_backend.task.RequestKeys.hash(agent_backend.task.RequestKeys.validate(key));
        String inputHash = agent_backend.task.RequestKeys.hash(input);
        String id = java.util.UUID.randomUUID().toString();
        // A duplicate INSERT waits for the first transaction before the row is read.
        jdbc.update("""
                INSERT IGNORE INTO request_dedup(user_id,operation,key_hash,request_hash,resource_id)
                VALUES (?,'create-run',?,?,?)
                """, userId, keyHash, inputHash, id);
        var row = jdbc.queryForMap("""
                SELECT request_hash,resource_id FROM request_dedup
                WHERE user_id=? AND operation='create-run' AND key_hash=? FOR UPDATE
                """, userId, keyHash);
        if (!inputHash.equals(row.get("request_hash")))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Idempotency-Key used with different input");
        String existing = (String) row.get("resource_id");
        if (id.equals(existing)) createOwned(id, input, userId);
        return existing;
    }

    @Override
    public Optional<String> findOwner(String taskId) {
        return jdbc.queryForList("SELECT user_id FROM agent_task WHERE task_id = ? AND user_id IS NOT NULL",
                String.class, taskId).stream().findFirst();
    }

    @Override
    @Transactional
    public TaskLease start(TaskLease lease, long durationMs) {
        Assert.isTrue(durationMs > 0, "Lease duration must be positive");
        var tokens = jdbc.queryForList("""
                SELECT fence_token FROM agent_task WHERE task_id = ? AND status = 'PENDING'
                    AND active_attempt_id IS NULL FOR UPDATE
                """, Long.class, lease.taskId());
        if (tokens.isEmpty()) throw new IllegalStateException("Run is not available for claim: " + lease.taskId());
        long token = Math.addExact(tokens.getFirst(), 1L);
        jdbc.update("""
                INSERT INTO run_attempt (run_id, attempt_id, attempt_number, fence_token, worker_id,
                    status, started_at, heartbeat_at, lease_until)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                    TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6)))
                """, lease.taskId(), lease.attemptId(), token, token, lease.workerId(), Math.multiplyExact(durationMs, 1000));
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'RUNNING', active_attempt_id = ?, fence_token = ?,
                    started_at = COALESCE(started_at, UTC_TIMESTAMP(6))
                WHERE task_id = ? AND status = 'PENDING'
                """, lease.attemptId(), token, lease.taskId()), "start run", lease.taskId());
        JdbcRunEvents.append(jdbc, lease.taskId(), "run.started", lease.attemptId(), null, null, "RUNNING");
        return new TaskLease(lease.taskId(), lease.workerId(), lease.attemptId(), token);
    }

    @Override
    @Transactional
    public boolean renew(TaskLease lease, long durationMs) {
        Assert.isTrue(durationMs > 0, "Lease duration must be positive");
        try { leases.lock(lease); }
        catch (agent_backend.persistence.LeaseLostException lost) { return false; }
        return jdbc.update("""
                UPDATE run_attempt SET heartbeat_at = UTC_TIMESTAMP(6),
                    lease_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                WHERE run_id = ? AND attempt_id = ? AND fence_token = ? AND worker_id = ?
                    AND status = 'RUNNING' AND lease_until > UTC_TIMESTAMP(6)
                """, Math.multiplyExact(durationMs, 1000), lease.taskId(), lease.attemptId(),
                lease.fenceToken(), lease.workerId()) == 1;
    }

    @Override
    @Transactional
    public void complete(TaskLease lease, String answer) {
        Assert.hasText(answer, "final answer must not be blank");
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'COMPLETED', final_answer = ?, finished_at = UTC_TIMESTAMP(6),
                    active_attempt_id = NULL
                WHERE task_id = ? AND status = 'RUNNING'
                  AND NOT EXISTS (SELECT 1 FROM agent_step WHERE task_id = ? AND status = 'RUNNING')
                """, answer, lease.taskId(), lease.taskId()), "complete task", lease.taskId());
        finishAttempt(lease.taskId(), lease.attemptId(), "SUCCEEDED", null);
        JdbcRunEvents.append(jdbc, lease.taskId(), "run.completed", lease.attemptId(), null, null, "COMPLETED");
    }

    @Override
    @Transactional
    public void completeFinal(TaskLease lease, String stepId, String answer) {
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET status='COMPLETED', finished_at=UTC_TIMESTAMP(6)
                WHERE step_id=? AND task_id=? AND status='RUNNING' AND model_response IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM agent_tool_call WHERE step_id=?)
                """, stepId, lease.taskId(), stepId), "complete final step", stepId);
        JdbcRunEvents.append(jdbc, lease.taskId(), "step.completed", lease.attemptId(), stepId, null, "COMPLETED");
        // This self-call intentionally shares the outer transaction: no partial final-step commit.
        complete(lease, answer);
    }

    @Override
    @Transactional
    public void fail(TaskLease lease, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        leases.lock(lease);
        closeUnfinishedChildren(lease.taskId(), reason);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6),
                    active_attempt_id = NULL
                WHERE task_id = ? AND status = 'RUNNING'
                """, reason, lease.taskId()), "fail task", lease.taskId());
        finishAttempt(lease.taskId(), lease.attemptId(), "FAILED", reason);
        JdbcRunEvents.append(jdbc, lease.taskId(), "run.failed", lease.attemptId(), null, null, "FAILED");
    }

    @Override
    @Transactional
    public void failPending(String taskId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'PENDING'
                """, reason, taskId), "fail pending task", taskId);
        JdbcRunEvents.append(jdbc, taskId, "run.failed", null, null, null, "FAILED");
    }

    @Override
    public List<String> findExpired(int limit) {
        Assert.isTrue(limit > 0, "limit must be positive");
        return jdbc.queryForList("""
                SELECT r.task_id FROM agent_task r LEFT JOIN run_attempt a
                    ON a.run_id = r.task_id AND a.attempt_id = r.active_attempt_id
                WHERE r.status = 'RUNNING'
                    AND (a.lease_until IS NULL OR a.lease_until <= UTC_TIMESTAMP(6))
                ORDER BY a.lease_until, r.task_id LIMIT ?
                """, String.class, limit);
    }

    @Override
    @Transactional
    public boolean recoverExpired(String taskId) {
        // Serialize against heartbeat and every writer via the same parent-row lock.
        var running = jdbc.queryForList("""
                SELECT task_id FROM agent_task WHERE task_id = ? AND status = 'RUNNING' FOR UPDATE
                """, String.class, taskId);
        if (running.isEmpty()) return false;
        String attemptId = jdbc.queryForObject("SELECT active_attempt_id FROM agent_task WHERE task_id = ?", String.class, taskId);
        if (attemptId != null) {
            var expired = jdbc.queryForList("""
                    SELECT attempt_id FROM run_attempt WHERE run_id = ? AND attempt_id = ? AND status = 'RUNNING'
                        AND (lease_until IS NULL OR lease_until <= UTC_TIMESTAMP(6)) FOR UPDATE
                    """, String.class, taskId, attemptId);
            if (expired.isEmpty()) return false;
        }
        String reason = "Task execution interrupted: worker lease expired";
        closeUnfinishedChildren(taskId, reason);
        if (attemptId != null) finishAttempt(taskId, attemptId, "EXPIRED", reason);
        jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6),
                    active_attempt_id = NULL
                WHERE task_id = ? AND status = 'RUNNING'
                """, reason, taskId);
        JdbcRunEvents.append(jdbc, taskId, "run.failed", attemptId, null, null, "FAILED");
        return true;
    }

    @Override
    public Optional<TaskRecord> find(String taskId) {
        return jdbc.query(RUN_SELECT + " WHERE r.task_id = ?",
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

    @Override
    public List<agent_backend.persistence.model.RunAttemptRecord> findAttempts(String taskId, String userId) {
        Assert.hasText(userId, "userId must not be blank");
        return jdbc.query("""
                SELECT a.* FROM run_attempt a JOIN agent_task r ON r.task_id = a.run_id
                WHERE a.run_id = ? AND r.user_id = ? ORDER BY a.attempt_number
                """, DataClassRowMapper.newInstance(agent_backend.persistence.model.RunAttemptRecord.class), taskId, userId);
    }

    private void finishAttempt(String runId, String attemptId, String status, String reason) {
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE run_attempt SET status = ?, ended_at = UTC_TIMESTAMP(6), reason = ?
                WHERE run_id = ? AND attempt_id = ? AND status = 'RUNNING'
                """, status, reason, runId, attemptId), "finish attempt", attemptId);
        JdbcRunEvents.append(jdbc, runId, "attempt." + status.toLowerCase(java.util.Locale.ROOT),
                attemptId, null, null, status);
    }
    private void closeUnfinishedChildren(String taskId, String reason) {
        String attemptId = jdbc.queryForObject("SELECT active_attempt_id FROM agent_task WHERE task_id=?", String.class, taskId);
        JdbcRunEvents.abortTools(jdbc, taskId, null, attemptId, reason);
        var ids = jdbc.queryForList("SELECT step_id FROM agent_step WHERE task_id=? AND status='RUNNING' ORDER BY step_number",
                String.class, taskId);
        for (String id : ids) {
            jdbc.update("UPDATE agent_step SET status='FAILED', failure_reason=?, finished_at=UTC_TIMESTAMP(6) WHERE step_id=?",
                    reason, id);
            JdbcRunEvents.append(jdbc, taskId, "step.failed", attemptId, id, null, "FAILED");
        }
    }
}

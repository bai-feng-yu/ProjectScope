package agent_backend.persistence.jdbc;

import java.util.List;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.TaskLease;
import org.springframework.transaction.annotation.Transactional;
import agent_backend.persistence.model.StepRecord;

@Repository
public class JdbcStepStore implements StepStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JdbcLeaseGuard leases;

    public JdbcStepStore(JdbcTemplate jdbc, ObjectMapper mapper, JdbcLeaseGuard leases) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.leases = leases;
    }

    @Override
    @Transactional
    public void create(TaskLease lease, String stepId, int number) {
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                INSERT INTO agent_step (step_id, task_id, step_number, created_attempt_id)
                SELECT ?, task_id, ?, ? FROM agent_task WHERE task_id = ? AND status = 'RUNNING'
                """, stepId, number, lease.attemptId(), lease.taskId()), "create step", stepId);
        JdbcRunEvents.append(jdbc, lease.taskId(), "model.started", lease.attemptId(), stepId, null, "RUNNING");
    }

    @Override
    @Transactional
    public void saveResponse(TaskLease lease, String stepId, Object response) {
        Assert.notNull(response, "LLM returned an empty response");
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET model_response = ?
                WHERE step_id = ? AND task_id = ? AND status = 'RUNNING' AND model_response IS NULL
                """, mapper.writeValueAsString(response), stepId, lease.taskId()), "save model response", stepId);
        JdbcRunEvents.append(jdbc, lease.taskId(), "model.completed", lease.attemptId(), stepId, null, "COMPLETED");
    }

    @Override
    @Transactional
    public void complete(TaskLease lease, String stepId) {
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET status = 'COMPLETED', finished_at = UTC_TIMESTAMP(6)
                WHERE step_id = ? AND task_id = ? AND status = 'RUNNING' AND model_response IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM agent_tool_call
                      WHERE step_id = ? AND status IN ('PENDING', 'RUNNING', 'UNKNOWN'))
                """, stepId, lease.taskId(), stepId), "complete step", stepId);
        JdbcRunEvents.append(jdbc, lease.taskId(), "step.completed", lease.attemptId(), stepId, null, "COMPLETED");
    }

    @Override
    @Transactional
    public void fail(TaskLease lease, String stepId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        leases.lock(lease);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE step_id = ? AND task_id = ? AND status = 'RUNNING'
                """, reason, stepId, lease.taskId()), "fail step", stepId);
        JdbcRunEvents.append(jdbc, lease.taskId(), "step.failed", lease.attemptId(), stepId, null, "FAILED");
    }

    @Override
    public List<StepRecord> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM agent_step WHERE task_id = ? ORDER BY step_number",
                DataClassRowMapper.newInstance(StepRecord.class), taskId);
    }
}

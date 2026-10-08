package agent_backend.persistence.jdbc;

import java.util.List;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.model.StepRecord;

@Repository
public class JdbcStepStore implements StepStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcStepStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void create(String stepId, String taskId, int number) {
        JdbcStoreSupport.requireOne(jdbc.update("""
                INSERT INTO agent_step (step_id, task_id, step_number)
                SELECT ?, task_id, ? FROM agent_task WHERE task_id = ? AND status = 'RUNNING'
                """, stepId, number, taskId), "create step", stepId);
    }

    @Override
    public void saveResponse(String stepId, Object response) {
        Assert.notNull(response, "LLM returned an empty response");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET model_response = ?
                WHERE step_id = ? AND status = 'RUNNING' AND model_response IS NULL
                """, mapper.writeValueAsString(response), stepId), "save model response", stepId);
    }

    @Override
    public void complete(String stepId) {
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET status = 'COMPLETED', finished_at = UTC_TIMESTAMP(6)
                WHERE step_id = ? AND status = 'RUNNING' AND model_response IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM agent_tool_call
                      WHERE step_id = ? AND status IN ('PENDING', 'RUNNING', 'UNKNOWN'))
                """, stepId, stepId), "complete step", stepId);
    }

    @Override
    public void fail(String stepId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_step SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE step_id = ? AND status = 'RUNNING'
                """, reason, stepId), "fail step", stepId);
    }

    @Override
    public List<StepRecord> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM agent_step WHERE task_id = ? ORDER BY step_number",
                DataClassRowMapper.newInstance(StepRecord.class), taskId);
    }
}

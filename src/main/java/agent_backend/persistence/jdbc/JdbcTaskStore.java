package agent_backend.persistence.jdbc;

import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.model.TaskRecord;

@Repository
public class JdbcTaskStore implements TaskStore {
    private final JdbcTemplate jdbc;

    public JdbcTaskStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void create(String taskId, String input) {
        Assert.hasText(input, "task must not be blank");
        jdbc.update("INSERT INTO agent_task (task_id, task_input) VALUES (?, ?)", taskId, input);
    }

    @Override
    public void start(String taskId) {
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'RUNNING', started_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'PENDING'
                """, taskId), "start task", taskId);
    }

    @Override
    public void complete(String taskId, String answer) {
        Assert.hasText(answer, "final answer must not be blank");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'COMPLETED', final_answer = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status = 'RUNNING'
                  AND NOT EXISTS (SELECT 1 FROM agent_step WHERE task_id = ? AND status = 'RUNNING')
                """, answer, taskId, taskId), "complete task", taskId);
    }

    @Override
    public void fail(String taskId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_task SET status = 'FAILED', failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                """, reason, taskId), "fail task", taskId);
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
}

package agent_backend.persistence.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/** Internal writer: joins the caller's state transaction; never publishes before commit. */
final class JdbcRunEvents {
    private JdbcRunEvents() {}

    static void append(JdbcTemplate jdbc, String runId, String type, String attemptId,
            String stepId, String invocationId, String status) {
        Assert.state(TransactionSynchronizationManager.isActualTransactionActive(),
                "Run events require the state transaction");
        // All writers lock the Run before its children. Sequence allocation rolls back with the event.
        long previous = jdbc.queryForObject(
                "SELECT last_event_seq FROM agent_task WHERE task_id = ? FOR UPDATE", Long.class, runId);
        long next = Math.addExact(previous, 1L);
        jdbc.update("UPDATE agent_task SET last_event_seq = ? WHERE task_id = ?", next, runId);
        jdbc.update("""
                INSERT INTO run_event (run_id, seq, event_type, attempt_id, step_id, invocation_id, payload)
                VALUES (?, ?, ?, ?, ?, ?, JSON_OBJECT('status', ?))
                """, runId, next, type, attemptId, stepId, invocationId, status);
    }

    static void tool(JdbcTemplate jdbc, String runId, String attemptId, String invocationId, String status) {
        String stepId = jdbc.queryForObject("SELECT step_id FROM agent_tool_call WHERE invocation_id = ?",
                String.class, invocationId);
        append(jdbc, runId, "tool." + ("RUNNING".equals(status) ? "started" : status.toLowerCase(java.util.Locale.ROOT)),
                attemptId, stepId, invocationId, status);
    }

    static void abortTools(JdbcTemplate jdbc, String runId, String stepId, String attemptId, String reason) {
        var rows = jdbc.queryForList("""
                SELECT c.invocation_id, c.status FROM agent_tool_call c JOIN agent_step s ON s.step_id=c.step_id
                WHERE s.task_id=? AND (? IS NULL OR s.step_id=?) AND c.status IN ('PENDING','RUNNING')
                ORDER BY s.step_number, c.call_order
                """, runId, stepId, stepId);
        for (var row : rows) {
            String id = (String) row.get("invocation_id");
            String status = "RUNNING".equals(row.get("status")) ? "UNKNOWN" : "FAILED";
            jdbc.update("UPDATE agent_tool_call SET status=?, failure_reason=?, finished_at=UTC_TIMESTAMP(6) "
                    + "WHERE invocation_id=?", status, reason, id);
            tool(jdbc, runId, attemptId, id, status);
        }
    }
}

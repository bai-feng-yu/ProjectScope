package agent_backend.persistence.jdbc;

import java.util.List;
import java.util.Map;
import agent_backend.security.TaskIdentity;
import agent_backend.tool.RecoveryPolicy;
import agent_backend.tool.Reconciliation;
import agent_backend.tool.result.ToolSuccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Recovery locks the Run first, but never holds a transaction while querying an external system. */
@Repository
public class JdbcToolRecoveryStore {
    public record Call(String runId, String invocationId, String stepId, String toolCallId, String toolName,
            String arguments, String idempotencyKey, RecoveryPolicy policy, String version, String status, String resolution) {}
    public record View(Call call, List<Map<String,Object>> executions, List<Map<String,Object>> recoveries) {}
    public record Claim(Call call, boolean replay) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcToolRecoveryStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View view(String runId, String invocationId, TaskIdentity identity) {
        owned(runId, identity, false);
        var call = call(runId, invocationId);
        return new View(call,
                jdbc.queryForList("SELECT * FROM tool_execution_attempt WHERE invocation_id=? ORDER BY started_at,execution_id", invocationId),
                jdbc.queryForList("SELECT * FROM tool_recovery_attempt WHERE invocation_id=? ORDER BY started_at,request_id", invocationId));
    }

    @Transactional
    public Claim begin(String runId, String invocationId, TaskIdentity identity, String requestId,
            String action, String hash) {
        String runStatus = owned(runId, identity, true);
        var call = call(runId, invocationId);
        var previous = jdbc.queryForList("SELECT * FROM tool_recovery_attempt WHERE request_id=?", requestId);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!invocationId.equals(row.get("invocation_id")) || !identity.userId().equals(row.get("actor_id"))
                    || !hash.equals(row.get("request_hash")) || !action.equals(row.get("action")))
                throw conflict("Recovery request ID already used");
            if ("DONE".equals(row.get("status"))) return new Claim(call, true);
            throw conflict("Recovery request is running or expired; use a new request ID after expiry");
        }
        if (!List.of("COMPLETED", "FAILED").contains(runStatus))
            throw conflict("Run must be terminal before recovery");
        if (!"UNKNOWN".equals(call.status()) || "NOT_EXECUTED".equals(call.resolution()))
            throw conflict("Invocation does not require recovery");
        if ("RECONCILE".equals(action) && call.policy() != RecoveryPolicy.RECONCILABLE
                && call.policy() != RecoveryPolicy.IDEMPOTENT)
            throw conflict("This tool requires manual confirmation or future safe replay; lookup is not enabled");
        if (!jdbc.queryForList("""
                SELECT request_id FROM tool_recovery_attempt
                WHERE invocation_id=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)
                """, invocationId).isEmpty()) throw conflict("Another recovery is running");
        int expired = jdbc.update("""
                UPDATE tool_recovery_attempt SET status='EXPIRED',ended_at=UTC_TIMESTAMP(6)
                WHERE invocation_id=? AND status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)
                """, invocationId);
        if (expired > 0) event(call, "tool.recovery.expired", "EXPIRED");
        jdbc.update("""
                INSERT INTO tool_recovery_attempt(request_id,invocation_id,actor_id,action,request_hash,lease_until)
                VALUES (?,?,?,?,?,TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6)))
                """, requestId, invocationId, identity.userId(), action, hash);
        event(call, "tool.recovery.started", "RUNNING");
        return new Claim(call, false);
    }

    @Transactional
    public View finish(String runId, String invocationId, TaskIdentity identity, String requestId, Reconciliation result) {
        owned(runId, identity, true);
        var call = call(runId, invocationId);
        if (!"UNKNOWN".equals(call.status())) throw conflict("Invocation already resolved");
        int updated = jdbc.update("""
                UPDATE tool_recovery_attempt SET status='DONE',outcome=?,evidence=?,output=?,ended_at=UTC_TIMESTAMP(6)
                WHERE request_id=? AND invocation_id=? AND actor_id=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)
                """, result.outcome().name(), result.evidence(), mapper.writeValueAsString(result.output()),
                requestId, invocationId, identity.userId());
        if (updated != 1) throw conflict("Recovery claim expired or superseded");
        if (result.outcome() == Reconciliation.Outcome.SUCCEEDED) {
            jdbc.update("""
                    UPDATE agent_tool_call SET status='SUCCEEDED',result=?,failure_reason=NULL,
                    recovery_resolution='SUCCEEDED',finished_at=UTC_TIMESTAMP(6) WHERE invocation_id=?
                    """, mapper.writeValueAsString(new ToolSuccess<>(call.toolCallId(), call.toolName(), result.output())), invocationId);
            event(call, "tool.succeeded", "SUCCEEDED");
        } else {
            jdbc.update("UPDATE agent_tool_call SET recovery_resolution=? WHERE invocation_id=?",
                    result.outcome().name(), invocationId);
        }
        // Preserve the original execution's UNKNOWN record and the terminal Run/Step.
        event(call, "tool.recovery.completed", result.outcome().name());
        return view(runId, invocationId, identity);
    }

    private String owned(String runId, TaskIdentity identity, boolean lock) {
        identity.requireValid();
        var rows = jdbc.queryForList("SELECT status FROM agent_task WHERE task_id=? AND user_id=?"
                + (lock ? " FOR UPDATE" : ""), String.class, runId, identity.userId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");
        return rows.getFirst();
    }
    private Call call(String runId, String invocationId) {
        return jdbc.query("""
                SELECT c.* FROM agent_tool_call c JOIN agent_step s ON s.step_id=c.step_id
                WHERE s.task_id=? AND c.invocation_id=?
                """, (rs, row) -> new Call(runId, invocationId, rs.getString("step_id"), rs.getString("tool_call_id"),
                rs.getString("tool_name"), rs.getString("arguments"), rs.getString("idempotency_key"),
                RecoveryPolicy.valueOf(rs.getString("recovery_policy")), rs.getString("recovery_version"),
                rs.getString("status"), rs.getString("recovery_resolution")), runId, invocationId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invocation not found"));
    }
    private void event(Call call, String type, String status) {
        JdbcRunEvents.append(jdbc, call.runId(), type, null, call.stepId(), call.invocationId(), status);
    }
    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}

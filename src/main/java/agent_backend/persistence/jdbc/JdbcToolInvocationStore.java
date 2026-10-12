package agent_backend.persistence.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.persistence.TaskLease;
import agent_backend.persistence.model.ToolInvocationRecord;
import agent_backend.tool.ToolInvocation;
import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolResult;

@Repository
public class JdbcToolInvocationStore implements ToolInvocationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JdbcLeaseGuard leases;
    private final agent_backend.toolRegistry.ToolRegistry registry;

    public JdbcToolInvocationStore(JdbcTemplate jdbc, ObjectMapper mapper, JdbcLeaseGuard leases,
            agent_backend.toolRegistry.ToolRegistry registry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.leases = leases;
        this.registry = registry;
    }

    @Override
    @Transactional
    public List<String> createBatch(TaskLease lease, String stepId, List<ToolInvocation> calls) {
        Assert.notEmpty(calls, "tool calls must not be empty");
        leases.lock(lease);
        String status = jdbc.queryForObject("SELECT status FROM agent_step WHERE step_id = ? AND task_id = ? FOR UPDATE",
                String.class, stepId, lease.taskId());
        Assert.state("RUNNING".equals(status), "Cannot add calls to a terminal step");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolInvocation call = calls.get(i);
            String id = UUID.randomUUID().toString();
            var policy = agent_backend.tool.RecoveryPolicy.MANUAL_ONLY;
            String version = "missing";
            agent_backend.tool.AgentTool<?, ?> adapter = null;
            try {
                adapter = registry.getTool(call.toolName());
            } catch (IllegalArgumentException missing) { /* Persist missing tools as explicit failures. */ }
            if (adapter != null) {
                policy = adapter.recoveryPolicy();
                version = adapter.recoveryVersion();
            }
            jdbc.update("""
                    INSERT INTO agent_tool_call
                        (invocation_id, step_id, tool_call_id, call_order, tool_name, arguments,
                         idempotency_key, recovery_policy, recovery_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, stepId, call.toolCallId(), i + 1, call.toolName(), mapper.writeValueAsString(call.arguments()),
                    id, policy.name(), version);
            JdbcRunEvents.append(jdbc, lease.taskId(), "tool.queued", lease.attemptId(), stepId, id, "PENDING");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    @Override
    @Transactional
    public void start(TaskLease lease, String invocationId) {
        leases.lock(lease);
        // Queue-time capability snapshot cannot silently change before execution.
        var contracts = jdbc.queryForList("""
                SELECT c.tool_name,c.recovery_policy,c.recovery_version FROM agent_tool_call c
                JOIN agent_step s ON s.step_id=c.step_id WHERE c.invocation_id=? AND s.task_id=?
                """, invocationId, lease.taskId());
        if (!contracts.isEmpty()) {
            var contract = contracts.getFirst();
            if (!"missing".equals(contract.get("recovery_version")) && !"legacy".equals(contract.get("recovery_version"))) {
                var adapter = registry.getTool((String) contract.get("tool_name"));
                Assert.state(adapter.recoveryPolicy().name().equals(contract.get("recovery_policy"))
                        && adapter.recoveryVersion().equals(contract.get("recovery_version")),
                        "Tool adapter changed after queueing");
            }
        }
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_tool_call c
                JOIN agent_step s ON s.step_id = c.step_id
                JOIN agent_task t ON t.task_id = s.task_id
                SET c.status = 'RUNNING', c.started_at = UTC_TIMESTAMP(6)
                WHERE c.invocation_id = ? AND c.status = 'PENDING' AND s.status = 'RUNNING' AND t.status = 'RUNNING' AND t.task_id = ?
                """, invocationId, lease.taskId()), "start tool call", invocationId);
        JdbcRunEvents.tool(jdbc, lease.taskId(), lease.attemptId(), invocationId, "RUNNING");
        jdbc.update("""
                INSERT INTO tool_execution_attempt(execution_id,invocation_id,run_id,run_attempt_id,status)
                VALUES (?,?,?,?,'RUNNING')
                """, UUID.randomUUID().toString(), invocationId, lease.taskId(), lease.attemptId());
    }

    @Override
    @Transactional
    public void finish(TaskLease lease, String invocationId, ToolResult<?> result) {
        Assert.notNull(result, "Tool result must not be null");
        leases.lock(lease);
        String status = result instanceof agent_backend.tool.result.ToolUncertain<?> ? "UNKNOWN"
                : result instanceof ToolFailure<?> ? "FAILED" : "SUCCEEDED";
        String reason = result instanceof ToolFailure<?> failure ? failure.errorMessage()
                : result instanceof agent_backend.tool.result.ToolUncertain<?> uncertain ? uncertain.errorMessage() : null;
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE agent_tool_call SET status = ?, result = ?, failure_reason = ?, finished_at = UTC_TIMESTAMP(6)
                WHERE invocation_id = ? AND status = 'RUNNING' AND tool_call_id = ?
                  AND step_id IN (SELECT step_id FROM agent_step WHERE task_id = ?)
                """, status, mapper.writeValueAsString(result), reason, invocationId, result.toolCallId(), lease.taskId()),
                "finish tool call", invocationId);
        JdbcRunEvents.tool(jdbc, lease.taskId(), lease.attemptId(), invocationId, status);
        JdbcStoreSupport.requireOne(jdbc.update("""
                UPDATE tool_execution_attempt SET status=?,result=?,failure_reason=?,ended_at=UTC_TIMESTAMP(6)
                WHERE invocation_id=? AND run_attempt_id=? AND status='RUNNING'
                """, status, mapper.writeValueAsString(result), reason, invocationId, lease.attemptId()),
                "finish tool execution", invocationId);
    }

    @Override
    @Transactional
    public void abortUnfinished(TaskLease lease, String stepId, String reason) {
        Assert.hasText(reason, "failure reason must not be blank");
        leases.lock(lease);
        JdbcRunEvents.abortTools(jdbc, lease.taskId(), stepId, lease.attemptId(), reason);
    }

    @Override
    public List<ToolInvocationRecord> findByStep(String stepId) {
        return jdbc.query("SELECT * FROM agent_tool_call WHERE step_id = ? ORDER BY call_order",
                DataClassRowMapper.newInstance(ToolInvocationRecord.class), stepId);
    }
}

package agent_backend.tool;

public record ToolRecoveryContext(String runId, String invocationId, String userId, String idempotencyKey) {}

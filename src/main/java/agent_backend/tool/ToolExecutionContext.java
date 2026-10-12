package agent_backend.tool;

import org.springframework.util.Assert;
import agent_backend.task.LeaseSession;

public record ToolExecutionContext(LeaseSession leaseSession, String stepId, String userId,
        String invocationId, String idempotencyKey) {
    public ToolExecutionContext(LeaseSession leaseSession, String stepId, String userId) {
        this(leaseSession, stepId, userId, null, null);
    }
    public ToolExecutionContext forInvocation(String id) {
        Assert.hasText(id, "invocationId must not be blank");
        // V6 uses the persisted internal invocation UUID as the immutable key.
        return new ToolExecutionContext(leaseSession, stepId, userId, id, id);
    }
    public ToolExecutionContext {
        Assert.notNull(leaseSession, "leaseSession must not be null");
        Assert.hasText(stepId, "stepId must not be blank");
    }
}

package agent_backend.tool;

import org.springframework.util.Assert;
import agent_backend.task.LeaseSession;

public record ToolExecutionContext(LeaseSession leaseSession, String stepId, String userId) {
    public ToolExecutionContext {
        Assert.notNull(leaseSession, "leaseSession must not be null");
        Assert.hasText(stepId, "stepId must not be blank");
    }
}

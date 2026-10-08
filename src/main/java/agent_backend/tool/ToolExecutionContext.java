package agent_backend.tool;

import org.springframework.util.Assert;

public record ToolExecutionContext(String taskId, String stepId) {
    public ToolExecutionContext {
        Assert.hasText(taskId, "taskId must not be blank");
        Assert.hasText(stepId, "stepId must not be blank");
    }
}

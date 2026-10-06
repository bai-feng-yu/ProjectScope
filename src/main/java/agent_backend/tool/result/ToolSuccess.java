package agent_backend.tool.result;

import org.springframework.util.Assert;

public record ToolSuccess<O>(
        String toolCallId,
        String toolName,
        O data
) implements ToolResult<O> {
    public ToolSuccess {
        Assert.hasText(toolCallId, "toolCallId must not be blank");
    }
}


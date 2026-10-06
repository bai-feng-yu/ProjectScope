package agent_backend.tool.result;

import org.springframework.util.Assert;

public record ToolFailure<O>(
        String toolCallId,
        String toolName,
        String errorCode,
        String errorMessage
) implements ToolResult<O> {
    public ToolFailure {
        Assert.hasText(toolCallId, "toolCallId must not be blank");
        if (errorMessage == null || errorMessage.isBlank()) {
            errorMessage = "Tool call failed";
        }
    }

}

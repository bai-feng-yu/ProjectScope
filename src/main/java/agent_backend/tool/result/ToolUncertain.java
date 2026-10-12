package agent_backend.tool.result;

/** Execution entered the adapter; an exception does not prove absence of external effects. */
public record ToolUncertain<O>(String toolCallId, String toolName, String errorMessage) implements ToolResult<O> {
    public ToolUncertain {
        org.springframework.util.Assert.hasText(toolCallId, "toolCallId is required");
    }
}

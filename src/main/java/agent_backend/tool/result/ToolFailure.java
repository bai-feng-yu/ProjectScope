package agent_backend.tool.result;

public record ToolFailure<O>(
        String toolName,
        String errorCode,
        String errorMessage
) implements ToolResult<O> {
}

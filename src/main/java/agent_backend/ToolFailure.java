package agent_backend;

public record ToolFailure<O>(
        String toolName,
        String errorCode,
        String errorMessage
) implements ToolResult<O> {
}

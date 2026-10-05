package agent_backend;


public record ToolSuccess<O>(
        String toolName,
        O data
) implements ToolResult<O> {
}


package agent_backend.tool.result;

public record ToolSuccess<O>(
        String toolName,
        O data
) implements ToolResult<O> {
}


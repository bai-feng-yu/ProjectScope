package agent_backend.tool;

public record ToolDescriptor(
    String name,
    String description,
    Object inputSchema,
    Object outputSchema,
    Object inputExample,
    Object outputExample
) {}
package agent_backend.tool;

public record ToolDescriptor(
    String name,
    String description,
    Object inputSchema,
    Object outputSchema,
    Object inputExample,
    Object outputExample,
    RecoveryPolicy recoveryPolicy
) {
    public ToolDescriptor(String name, String description, Object inputSchema, Object outputSchema,
            Object inputExample, Object outputExample) {
        this(name, description, inputSchema, outputSchema, inputExample, outputExample, RecoveryPolicy.MANUAL_ONLY);
    }
}

package agent_backend.toolRegistry.discovery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import agent_backend.tool.AgentTool;
import agent_backend.tool.InvalidToolArgumentException;
import agent_backend.tool.ToolDescriptor;
import agent_backend.toolRegistry.ToolRegistry;

@Component
public class DescribeToolTool implements AgentTool<DescribeToolInput, ToolDescriptor> {
    private final ObjectProvider<ToolRegistry> registry;

    public DescribeToolTool(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Override public String name() { return "describe_tool"; }
    @Override public String description() { return "Get a tool's full description, parameter schema, and examples by name."; }
    @Override public Class<DescribeToolInput> inputType() { return DescribeToolInput.class; }
    @Override public Class<ToolDescriptor> outputType() { return ToolDescriptor.class; }
    @Override public DescribeToolInput inputExample() { return new DescribeToolInput("CalculateTool"); }
    @Override public ToolDescriptor outputExample() {
        return new ToolDescriptor("CalculateTool", "A tool for calculating expressions",
                java.util.Map.of("type", "object"), java.util.Map.of("type", "object"),
                java.util.Map.of("operation", "add", "left", 1, "right", 2),
                java.util.Map.of("toolOutput", 3));
    }

    @Override
    public ToolDescriptor execute(DescribeToolInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new InvalidToolArgumentException("name must not be blank");
        }
        return registry.getObject().describe(input.name());
    }
}

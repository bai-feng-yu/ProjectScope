package agent_backend.tools;

import java.util.List;
import org.springframework.stereotype.Component;
import agent_backend.ToolRegistry;
import java.util.Map;
import agent_backend.ToolResult;
import agent_backend.AgentTool;


@Component 
public class GetTools implements AgentTool {

    private final ToolRegistry toolRegistry;

    public GetTools(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override 
    public String name() {
        return "GetTools";
    }

    @Override  
    public String description() {
        return "Get all available tools";
    }

    @Override 
    public ToolResult execute(Map<String, Object> arguments) {
        List<AgentTool> tools = toolRegistry.getAllTools();
        StringBuilder toolList = new StringBuilder();

        for (AgentTool tool : tools) {
            toolList.append("Name: ").append(tool.name())
                    .append(", Description: ").append(tool.description())
                    .append("\n");
        }

        return new ToolResult(name(), toolList.toString());
    }
}

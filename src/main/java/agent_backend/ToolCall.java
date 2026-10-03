package agent_backend;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service 
public class ToolCall {
    private final ToolRegistry toolRegistry;

    public ToolCall(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ToolResult callTool(Map<String, Object> arguments){
        String toolName = (String) arguments.get("toolName");
        AgentTool tool = toolRegistry.getTool(toolName);
        if(tool == null){
            return new ToolResult(toolName,"Tool not found");
        }
        return tool.execute(arguments);
    }

}


package agent_backend;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ToolCall {
    private final ToolRegistry toolRegistry;

    public ToolCall(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ToolResult callTool(String toolName, Map<String, Object> arguments) {
        try{
            AgentTool tool = toolRegistry.getTool(toolName);
            return tool.execute(arguments);
        } catch (IllegalArgumentException exception) {
            return new ToolResult(toolName, exception.getMessage());
        } catch (Exception e) {
            return new ToolResult(
                toolName,
                "Tool execution failed: " + e.getMessage()
            );
        }
    }
}

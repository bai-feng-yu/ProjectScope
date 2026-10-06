package agent_backend.tool;

import org.springframework.stereotype.Service;

import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolResult;
import agent_backend.toolRegistry.ToolRegistry;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ToolCall {
    private final ToolRegistry toolRegistry;
    private final JsonMapper objectMapper;

    public ToolCall(ToolRegistry toolRegistry, JsonMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public ToolResult<?> callTool(String toolName, Object arguments) {
        try{
            AgentTool<?,?> tool = toolRegistry.getTool(toolName);

            return tool.executeRaw(arguments, objectMapper);
        } catch (IllegalArgumentException exception) {
            return new ToolFailure<>(toolName, "INVALID_ARGUMENT", exception.getMessage());
        } catch (Exception e) {
            return new ToolFailure<>(
                toolName,
                "INTERNAL_ERROR"  , e.getMessage()
            );
        }
    }
}

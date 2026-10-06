package agent_backend.tool;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolResult;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.toolRegistry.ToolRegistry;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ToolCall {
    private final ToolRegistry toolRegistry;
    private final JsonMapper objectMapper;
    private final ToolSchemaValidator schemaValidator;

    public ToolCall(ToolRegistry toolRegistry, JsonMapper objectMapper, ToolSchemaValidator schemaValidator) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
    }

    public ToolResult<?> callTool(String toolCallId, String toolName, Object arguments) {
        Assert.hasText(toolCallId, "toolCallId must not be blank");
        AgentTool<?, ?> tool;
        try {
            tool = toolRegistry.getTool(toolName);
        } catch (IllegalArgumentException exception) {
            return new ToolFailure<>(toolCallId, toolName, "TOOL_NOT_FOUND", exception.getMessage());
        }

        try {
            var errors = schemaValidator.validate(toolRegistry.describe(toolName).inputSchema(), arguments);
            if (!errors.isEmpty()) {
                return new ToolFailure<>(toolCallId, toolName, "INVALID_ARGUMENT", String.join("; ", errors));
            }
            return execute(toolCallId, tool, arguments);
        } catch (Exception e) {
            return new ToolFailure<>(
                toolCallId, toolName,
                "INTERNAL_ERROR", e.getMessage()
            );
        }
    }

    private <I, O> ToolResult<O> execute(String toolCallId, AgentTool<I, O> tool, Object arguments) {
        I input;
        try {
            input = objectMapper.convertValue(arguments, tool.inputType());
        } catch (Exception e) {
            return new ToolFailure<>(toolCallId, tool.name(), "INVALID_ARGUMENT", e.getMessage());
        }

        try {
            return new ToolSuccess<>(toolCallId, tool.name(), tool.execute(input));
        } catch (InvalidToolArgumentException e) {
            return new ToolFailure<>(toolCallId, tool.name(), "INVALID_ARGUMENT", e.getMessage());
        } catch (Exception e) {
            return new ToolFailure<>(toolCallId, tool.name(), "TOOL_EXECUTION_FAILED", e.getMessage());
        }
    }
}

package agent_backend.tool.result;

public sealed interface ToolResult<O>
        permits ToolSuccess, ToolFailure {
    String toolCallId();
}

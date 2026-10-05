package agent_backend;

public sealed interface ToolResult<O>
        permits ToolSuccess, ToolFailure {
}

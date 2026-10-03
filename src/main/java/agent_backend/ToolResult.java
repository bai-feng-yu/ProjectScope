package agent_backend;

public class ToolResult {
    private String toolName;

    private String toolOutput;

    public ToolResult(String toolName, String toolOutput){
        this.toolName = toolName;
        this.toolOutput = toolOutput;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolOutput() {
        return toolOutput;
    }
}

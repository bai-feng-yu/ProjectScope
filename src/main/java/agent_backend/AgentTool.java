package agent_backend;

import java.util.Map;


public interface AgentTool {
    public String name();
    public String description();
    public ToolResult execute(Map<String, Object> arguments);
}


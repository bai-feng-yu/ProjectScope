
package agent_backend;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component 
public class ToolRegistry {
    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = new HashMap<>();

        for (AgentTool tool : toolList) {
            this.tools.put(tool.name(), tool);
        }
    }

    public AgentTool getTool(String name) {
        AgentTool tool = tools.get(name);

        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }

        return tool;
    }

    public List<AgentTool> getAllTools() {
        return List.copyOf(tools.values());
    }
}
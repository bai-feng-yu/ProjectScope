
package agent_backend.toolRegistry;

import org.springframework.stereotype.Component;

import agent_backend.tool.AgentTool;
import agent_backend.tool.ToolDescriptor;
import agent_backend.tool.ToolDescriptorFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component 
public class ToolRegistry {
    private final Map<String, AgentTool<?,?>> tools;
    private final Map<String, ToolDescriptor> descriptors;

    public ToolRegistry(List<AgentTool<?,?>> toolList, ToolDescriptorFactory descriptorFactory) {
        Map<String, AgentTool<?, ?>> registeredTools = new LinkedHashMap<>();
        Map<String, ToolDescriptor> registeredDescriptors = new LinkedHashMap<>();

        for (AgentTool<?,?> tool : toolList) {
            if (registeredTools.containsKey(tool.name())) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
            registeredTools.put(tool.name(), tool);
            registeredDescriptors.put(tool.name(), descriptorFactory.create(tool));
        }
        this.tools = Map.copyOf(registeredTools);
        this.descriptors = Map.copyOf(registeredDescriptors);
    }


    public AgentTool<?,?> getTool(String name) {
        AgentTool<?,?> tool = tools.get(name);

        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }

        return tool;
    }

    public List<AgentTool<?,?>> getAllTools() {
        return tools.values().stream().sorted(Comparator.comparing(AgentTool::name)).toList();
    }

    public ToolDescriptor describe(String name) {
        ToolDescriptor descriptor = descriptors.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return descriptor;
    }

    public List<ToolDescriptor> search(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<ToolDescriptor> matches = new ArrayList<>();
        for (ToolDescriptor descriptor : descriptors.values()) {
            if (descriptor.name().toLowerCase(Locale.ROOT).contains(needle)
                    || descriptor.description().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(descriptor);
            }
        }
        matches.sort(Comparator.comparing(ToolDescriptor::name));
        return List.copyOf(matches);
    }
}

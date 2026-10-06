package agent_backend.toolRegistry.discovery;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import agent_backend.tool.AgentTool;
import agent_backend.tool.InvalidToolArgumentException;
import agent_backend.toolRegistry.ToolRegistry;

@Component
public class SearchToolsTool implements AgentTool<SearchToolsInput, SearchToolsOutput> {
    private final ObjectProvider<ToolRegistry> registry;

    public SearchToolsTool(ObjectProvider<ToolRegistry> registry) {
        this.registry = registry;
    }

    @Override public String name() { return "search_tools"; }
    @Override public String description() { return "Search available tools by name or description."; }
    @Override public Class<SearchToolsInput> inputType() { return SearchToolsInput.class; }
    @Override public Class<SearchToolsOutput> outputType() { return SearchToolsOutput.class; }
    @Override public SearchToolsInput inputExample() { return new SearchToolsInput("calculate"); }
    @Override public SearchToolsOutput outputExample() {
        return new SearchToolsOutput(List.of(new ToolSummary("CalculateTool", "A tool for calculating expressions")));
    }

    @Override
    public SearchToolsOutput execute(SearchToolsInput input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            throw new InvalidToolArgumentException("query must not be blank");
        }
        return new SearchToolsOutput(registry.getObject().search(input.query()).stream()
                .map(descriptor -> new ToolSummary(descriptor.name(), descriptor.description()))
                .toList());
    }
}

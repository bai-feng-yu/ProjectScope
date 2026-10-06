package agent_backend;

import java.util.List;
import org.springframework.util.Assert;
import agent_backend.tool.result.ToolResult;

/** One model turn and all its tool results. */
public class AgentStep {
    private final LlmDecision decision;
    private final List<ToolResult<?>> toolResults;

    public AgentStep(LlmDecision decision, List<ToolResult<?>> toolResults) {
        Assert.notNull(decision, "decision must not be null");
        this.decision = decision;
        this.toolResults = List.copyOf(toolResults);
        Assert.isTrue(decision.getToolCalls().size() == this.toolResults.size(),
                "Each tool call must have a result");
        for (int i = 0; i < this.toolResults.size(); i++) {
            Assert.isTrue(decision.getToolCalls().get(i).toolCallId()
                    .equals(this.toolResults.get(i).toolCallId()), "Tool result ID must match its call");
        }
    }

    public LlmDecision getDecision() { return decision; }
    public List<ToolResult<?>> getToolResults() { return toolResults; }
}

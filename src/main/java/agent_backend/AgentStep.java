package agent_backend;

import java.util.List;
import org.springframework.util.Assert;
import agent_backend.tool.result.ToolResult;

/** One model turn and all its tool results. */
public class AgentStep {
    private final String stepId;
    private final int stepNumber;
    private final LlmDecision decision;
    private final List<ToolResult<?>> toolResults;

    public AgentStep(String stepId, int stepNumber, LlmDecision decision, List<ToolResult<?>> toolResults) {
        Assert.hasText(stepId, "stepId must not be blank");
        Assert.isTrue(stepNumber > 0, "stepNumber must be positive");
        this.stepId = stepId;
        this.stepNumber = stepNumber;
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

    public String getStepId() { return stepId; }
    public int getStepNumber() { return stepNumber; }
    public LlmDecision getDecision() { return decision; }
    public List<ToolResult<?>> getToolResults() { return toolResults; }
}

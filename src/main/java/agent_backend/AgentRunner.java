package agent_backend;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import agent_backend.tool.ToolCall;
import agent_backend.tool.result.ToolResult;
import agent_backend.toolRegistry.ToolRegistry;

@Service
public class AgentRunner {
    private final LlmClient llmClient;
    private final ToolCall toolCall;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;

    public AgentRunner(LlmClient llmClient, ToolCall toolCall, ToolRegistry toolRegistry,
            @Value("${agent.max-steps:10}") int maxSteps) {
        this.llmClient = llmClient;
        this.toolCall = toolCall;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
    }

    public AgentState run(String task) {
        AgentState state = new AgentState(task, toolRegistry.getAllTools());

        for (int step = 0; step < maxSteps; step++) {
            LlmDecision decision = llmClient.decide(state);
            if (decision.getDecisionType() == LlmDecision.DecsionType.TOOL_CALL) {
                Map<String, Object> arguments = decision.getToolCallArguments();
                ToolResult<?> result = toolCall.callTool(
                        decision.getToolCallId(), decision.getToolName(), arguments);
                state.addStep(new AgentStep(decision, result));
            } else {
                state.complete(decision.getFinalAnswer());
                return state;
            }
        }

        state.fail();
        throw new IllegalStateException("Agent exceeded maximum steps: " + maxSteps);
    }
}

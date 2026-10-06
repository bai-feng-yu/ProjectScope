package agent_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import agent_backend.tool.ToolBatchExecutor;
import agent_backend.toolRegistry.ToolRegistry;

@Service
public class AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private final LlmClient llmClient;
    private final ToolBatchExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;

    public AgentRunner(LlmClient llmClient, ToolBatchExecutor toolExecutor, ToolRegistry toolRegistry,
            @Value("${agent.max-steps:10}") int maxSteps) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
    }

    public AgentState run(String task) {
        AgentState state = new AgentState(task);

        try {
            Assert.hasText(task, "task must not be blank");
            state.setTools(toolRegistry.getAllTools());
            for (int step = 0; step < maxSteps; step++) {
                LlmDecision decision = llmClient.decide(state);
                if (decision.getDecisionType() == LlmDecision.DecsionType.TOOL_CALL) {
                    var results = toolExecutor.execute(decision.getToolCalls());
                    state.addStep(new AgentStep(decision, results));
                } else {
                    state.complete(decision.getFinalAnswer());
                    return state;
                }
            }

            state.fail("Agent exceeded maximum steps: " + maxSteps);
        } catch (Exception exception) {
            log.error("Agent execution failed", exception);
            String message = exception.getMessage();
            state.fail(exception.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message));
        }

        return state;
    }
}

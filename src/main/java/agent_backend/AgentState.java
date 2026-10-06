package agent_backend;

import java.util.ArrayList;
import java.util.List;

import agent_backend.tool.AgentTool;
import org.springframework.util.Assert;

public class AgentState {
    private final List<AgentStep> steps;
    private final String task;
    private String finalAnswer;
    private String failureReason;
    private AgentStateType agentStateType;
    private List<AgentTool<?, ?>> tools = List.of();

    public enum AgentStateType { COMPLETED, RUNNING, FAILED }

    AgentState(String task) {
        this.task = task;
        this.agentStateType = AgentStateType.RUNNING;
        this.steps = new ArrayList<>();
    }

    public List<AgentTool<?, ?>> getTools() { return tools; }
    public AgentStateType getAgentStateType() { return agentStateType; }
    public List<AgentStep> getSteps() { return List.copyOf(steps); }
    public void addStep(AgentStep step) {
        requireRunning();
        Assert.notNull(step, "step must not be null");
        steps.add(step);
    }
    public String getTask() { return task; }
    public String getFinalAnswer() { return finalAnswer; }
    public String getFailureReason() { return failureReason; }

    void setTools(List<AgentTool<?, ?>> tools) {
        requireRunning();
        this.tools = List.copyOf(tools);
    }

    public void complete(String answer) {
        requireRunning();
        Assert.hasText(answer, "final answer must not be blank");
        this.finalAnswer = answer;
        this.agentStateType = AgentStateType.COMPLETED;
    }

    public void fail(String reason) {
        requireRunning();
        Assert.hasText(reason, "failure reason must not be blank");
        this.failureReason = reason;
        this.agentStateType = AgentStateType.FAILED;
    }

    private void requireRunning() {
        if (agentStateType != AgentStateType.RUNNING) {
            throw new IllegalStateException("Cannot modify a terminal agent state: " + agentStateType);
        }
    }
}

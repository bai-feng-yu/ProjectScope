package agent_backend;

import java.util.ArrayList;
import java.util.List;

public class AgentState {
    private final List<AgentStep> steps;
    private final String task;
    private String finalAnswer;
    private AgentStateType agentStateType;
    private final List<AgentTool<?, ?>> tools;

    public enum AgentStateType { COMPLETED, RUNNING, FAILED }

    AgentState(String task, List<AgentTool<?, ?>> tools) {
        this.task = task;
        this.agentStateType = AgentStateType.RUNNING;
        this.tools = List.copyOf(tools);
        this.steps = new ArrayList<>();
    }

    public List<AgentTool<?, ?>> getTools() { return tools; }
    public AgentStateType getAgentStateType() { return agentStateType; }
    public List<AgentStep> getSteps() { return steps; }
    public void addStep(AgentStep step) { steps.add(step); }
    public String getTask() { return task; }
    public String getFinalAnswer() { return finalAnswer; }

    public void complete(String answer) {
        this.finalAnswer = answer;
        this.agentStateType = AgentStateType.COMPLETED;
    }

    public void fail() { this.agentStateType = AgentStateType.FAILED; }
}

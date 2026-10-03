package agent_backend;

import java.util.List;

public class AgentState {
    private List<AgentStep> steps;
    private String task;
    private String finalAnswer;
    private AgentStateType agentStateType;

    private enum AgentStateType{
        COMPLETED,
        RUNNING,
        FAILED
    }

    AgentState(String task){
        this.task = task;
        this.agentStateType = AgentStateType.RUNNING;
    }

    public void setAgentStateType(AgentStateType agentStateType) {
        this.agentStateType = agentStateType;
    }

    public AgentStateType getAgentStateType() {
        return agentStateType;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void setSteps(List<AgentStep> steps) {
        this.steps = steps;
    }

    public void addStep(AgentStep step) {
        steps.add(step);
    }
    
    public String getTask() {
        return task;
    }

    public String finalAnswer() {
        return finalAnswer;
    }
    public String getFinalAnswer() {
        return finalAnswer;
    }
}

package agent_backend;

import java.util.List;

public record AgentRunResult(
        String task,
        AgentState.AgentStateType status,
        String finalAnswer,
        String failureReason,
        List<AgentStep> steps
) {
    public AgentRunResult {
        steps = List.copyOf(steps);
    }

    public static AgentRunResult from(AgentState state) {
        return new AgentRunResult(state.getTask(), state.getAgentStateType(),
                state.getFinalAnswer(), state.getFailureReason(), state.getSteps());
    }
}

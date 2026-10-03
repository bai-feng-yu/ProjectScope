package agent_backend;

public class AgentStep {
    private LlmDecision decision;

    private ToolResult toolResult;
    
    public AgentStep(LlmDecision decision, ToolResult toolResult){
        this.decision = decision;
        this.toolResult = toolResult;
    }

    public LlmDecision getDecision() {
        return decision;

    }

    public ToolResult getToolResult() { 
        return toolResult;
    }

}

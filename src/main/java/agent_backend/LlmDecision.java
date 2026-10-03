package agent_backend;

import java.util.Map;

public class LlmDecision {

    private DecsionType decisionType;

    private Map<String, Object> toolCallArguments;

    private String finalAnswer;

    public enum DecsionType{
    TOOL_CALL,
    FINAL_ANSWER
}

    public LlmDecision(DecsionType decisionType
        ,Map<String, Object> toolCallArguments
        ,String finalAnswer
        ){

            this.decisionType = decisionType;
            this.toolCallArguments = toolCallArguments;
            this.finalAnswer = finalAnswer;
    }

    public DecsionType getDecisionType() {
        return this.decisionType;
    }

    public Map<String, Object> getToolCallArguments() {
        return this.toolCallArguments;
    }

    public String getFinalAnswer() {
        return this.finalAnswer;
    }


}



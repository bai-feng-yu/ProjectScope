package agent_backend;

import java.util.Map;

public class LlmDecision {
    private final DecsionType decisionType;
    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> toolCallArguments;
    private final String finalAnswer;

    public enum DecsionType { TOOL_CALL, FINAL_ANSWER }

    private LlmDecision(DecsionType decisionType, String toolCallId, String toolName,
            Map<String, Object> toolCallArguments, String finalAnswer) {
        this.decisionType = decisionType;
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.toolCallArguments = toolCallArguments;
        this.finalAnswer = finalAnswer;
    }

    public static LlmDecision toolCall(String toolCallId, String toolName,
            Map<String, Object> arguments) {
        return new LlmDecision(DecsionType.TOOL_CALL, toolCallId, toolName, arguments, null);
    }

    public static LlmDecision finalAnswer(String answer) {
        return new LlmDecision(DecsionType.FINAL_ANSWER, null, null, null, answer);
    }

    public DecsionType getDecisionType() { return decisionType; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getToolCallArguments() { return toolCallArguments; }
    public String getFinalAnswer() { return finalAnswer; }
}

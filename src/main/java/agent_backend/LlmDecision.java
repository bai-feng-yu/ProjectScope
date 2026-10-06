package agent_backend;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import agent_backend.tool.ToolInvocation;
import org.springframework.util.Assert;

public class LlmDecision {
    private final DecsionType decisionType;
    private final List<ToolInvocation> toolCalls;
    private final String content;

    public enum DecsionType { TOOL_CALL, FINAL_ANSWER }

    private LlmDecision(DecsionType type, List<ToolInvocation> calls, String content) {
        this.decisionType = type;
        this.toolCalls = List.copyOf(calls);
        this.content = content;
    }

    public static LlmDecision toolCalls(List<ToolInvocation> calls, String content) {
        Assert.notEmpty(calls, "toolCalls must not be empty");
        var ids = new HashSet<String>();
        for (ToolInvocation call : calls) {
            Assert.isTrue(ids.add(call.toolCallId()), "Duplicate toolCallId in model turn");
        }
        return new LlmDecision(DecsionType.TOOL_CALL, calls, content);
    }

    public static LlmDecision toolCall(String id, String name, Map<String, Object> arguments) {
        return toolCalls(List.of(new ToolInvocation(id, name, arguments)), null);
    }

    public static LlmDecision finalAnswer(String answer) {
        return new LlmDecision(DecsionType.FINAL_ANSWER, List.of(), answer);
    }

    public DecsionType getDecisionType() { return decisionType; }
    public List<ToolInvocation> getToolCalls() { return toolCalls; }
    public String getContent() { return content; }
    public String getFinalAnswer() { return decisionType == DecsionType.FINAL_ANSWER ? content : null; }
}

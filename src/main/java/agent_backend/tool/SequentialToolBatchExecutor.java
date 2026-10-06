package agent_backend.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import agent_backend.tool.result.ToolResult;

@Service
public class SequentialToolBatchExecutor implements ToolBatchExecutor {
    private final ToolCall toolCall;

    public SequentialToolBatchExecutor(ToolCall toolCall) {
        this.toolCall = toolCall;
    }

    @Override
    public List<ToolResult<?>> execute(List<ToolInvocation> calls) {
        List<ToolResult<?>> results = new ArrayList<>();
        for (ToolInvocation call : calls) {
            results.add(toolCall.callTool(call.toolCallId(), call.toolName(), call.arguments()));
        }
        return List.copyOf(results);
    }
}

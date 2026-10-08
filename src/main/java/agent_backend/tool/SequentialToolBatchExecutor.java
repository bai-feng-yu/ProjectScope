package agent_backend.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.tool.result.ToolResult;

@Service
public class SequentialToolBatchExecutor implements ToolBatchExecutor {
    private final ToolCall toolCall;
    private final ToolInvocationStore store;

    public SequentialToolBatchExecutor(ToolCall toolCall, ToolInvocationStore store) {
        this.toolCall = toolCall;
        this.store = store;
    }

    @Override
    public List<ToolResult<?>> execute(ToolExecutionContext context, List<ToolInvocation> calls) {
        List<String> ids = store.createBatch(context.stepId(), calls);
        Assert.state(ids.size() == calls.size(), "Persisted batch size does not match tool calls");
        List<ToolResult<?>> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolInvocation call = calls.get(i);
            store.start(ids.get(i));
            ToolResult<?> result = toolCall.callTool(call.toolCallId(), call.toolName(), call.arguments());
            Assert.state(result != null && call.toolCallId().equals(result.toolCallId()),
                    "Tool result ID must match its call");
            // Commit each result before executing the next call. Never retry a side effect here.
            store.finish(ids.get(i), result);
            results.add(result);
        }
        return List.copyOf(results);
    }
}

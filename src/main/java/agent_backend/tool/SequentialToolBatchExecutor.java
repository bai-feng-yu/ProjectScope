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
        var session = context.leaseSession();
        var lease = session.lease();
        session.check();
        List<String> ids = store.createBatch(lease, context.stepId(), calls);
        Assert.state(ids.size() == calls.size(), "Persisted batch size does not match tool calls");
        List<ToolResult<?>> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolInvocation call = calls.get(i);
            session.check();
            store.start(lease, ids.get(i));
            session.check();
            ToolResult<?> result = toolCall.callTool(call.toolCallId(), call.toolName(), call.arguments(),
                    context.forInvocation(ids.get(i)));
            Assert.state(result != null && call.toolCallId().equals(result.toolCallId()),
                    "Tool result ID must match its call");
            // Commit each result before executing the next call. Never retry a side effect here.
            session.check();
            store.finish(lease, ids.get(i), result);
            if (result instanceof agent_backend.tool.result.ToolUncertain<?>)
                throw new IllegalStateException("Tool outcome is UNKNOWN; recovery required: " + ids.get(i));
            results.add(result);
        }
        return List.copyOf(results);
    }
}

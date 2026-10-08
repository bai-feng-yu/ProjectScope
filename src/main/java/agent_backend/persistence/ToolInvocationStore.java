package agent_backend.persistence;

import java.util.List;
import agent_backend.persistence.model.ToolInvocationRecord;
import agent_backend.tool.ToolInvocation;
import agent_backend.tool.result.ToolResult;

public interface ToolInvocationStore {
    /** Atomically inserts the entire batch; returns internal invocation IDs in call order. */
    List<String> createBatch(String stepId, List<ToolInvocation> calls);
    void start(String invocationId);
    void finish(String invocationId, ToolResult<?> result);
    /** Keeps saved results; marks RUNNING as UNKNOWN and unstarted PENDING as FAILED. */
    void abortUnfinished(String stepId, String reason);
    List<ToolInvocationRecord> findByStep(String stepId);
}

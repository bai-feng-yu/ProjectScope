package agent_backend.tool;

import java.util.List;
import agent_backend.tool.result.ToolResult;

/** Returns one result per call, in input order. */
public interface ToolBatchExecutor {
    List<ToolResult<?>> execute(List<ToolInvocation> calls);
}

package agent_backend.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import agent_backend.tool.AgentTool;
import agent_backend.tool.RecoveryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Server policy is independent of model-provided tool names and arguments. */
@Service
public class ToolAccess {
    private final JdbcTemplate jdbc;
    private final Set<String> allowed;

    public ToolAccess(JdbcTemplate jdbc,
            @Value("${agent.tools.allowed:CalculateTool,search_tools,describe_tool,web_search}") String allowed) {
        this.jdbc = jdbc;
        this.allowed = Arrays.stream(allowed.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
    }

    public void requireUser(String userId) {
        if (userId != null && !jdbc.queryForList(
                "SELECT user_id FROM app_user WHERE user_id=? AND enabled=FALSE", String.class, userId).isEmpty())
            throw new AccessDeniedException("Account disabled");
    }

    public void requireTool(String userId, AgentTool<?, ?> tool) {
        requireUser(userId);
        if (tool.recoveryPolicy() != RecoveryPolicy.READ_ONLY || !allowed.contains(tool.name())
                || !jdbc.queryForList("SELECT tool_name FROM disabled_tool WHERE tool_name=?", String.class, tool.name()).isEmpty())
            throw new AccessDeniedException("Tool is not allowed by the read-only MVP policy");
    }
}

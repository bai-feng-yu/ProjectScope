package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import agent_backend.security.ToolAccess;
import agent_backend.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

class ToolAccessTests {
    @Test void serverWhitelistReadOnlyCapabilityAndLiveRevocationAreRequired() {
        var jdbc = mock(JdbcTemplate.class);
        var policy = new ToolAccess(jdbc, "read");
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("read");
        when(tool.recoveryPolicy()).thenReturn(RecoveryPolicy.READ_ONLY);
        assertDoesNotThrow(() -> policy.requireTool("alice", tool));
        when(tool.recoveryPolicy()).thenReturn(RecoveryPolicy.IDEMPOTENT);
        assertThrows(AccessDeniedException.class, () -> policy.requireTool("alice", tool));
        when(tool.recoveryPolicy()).thenReturn(RecoveryPolicy.READ_ONLY);
        when(tool.name()).thenReturn("other");
        assertThrows(AccessDeniedException.class, () -> policy.requireTool("alice", tool));
        when(tool.name()).thenReturn("read");
        when(jdbc.queryForList("SELECT tool_name FROM disabled_tool WHERE tool_name=?", String.class, "read"))
                .thenReturn(List.of("read"));
        assertThrows(AccessDeniedException.class, () -> policy.requireTool("alice", tool));
        when(jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? AND enabled=FALSE", String.class, "alice"))
                .thenReturn(List.of("alice"));
        assertThrows(AccessDeniedException.class, () -> policy.requireUser("alice"));
    }
}

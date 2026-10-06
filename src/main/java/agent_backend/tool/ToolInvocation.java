package agent_backend.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.Assert;

/** A model-issued call, including its protocol correlation ID. */
public record ToolInvocation(String toolCallId, String toolName, Map<String, Object> arguments) {
    public ToolInvocation {
        Assert.hasText(toolCallId, "toolCallId must not be blank");
        Assert.hasText(toolName, "toolName must not be blank");
        Assert.notNull(arguments, "arguments must not be null");
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}

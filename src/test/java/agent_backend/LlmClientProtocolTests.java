package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import agent_backend.tool.ToolDescriptorFactory;
import agent_backend.tool.result.ToolFailure;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class LlmClientProtocolTests {
    private final LlmClient client = new LlmClient(JsonMapper.builder().build(),
            new ToolDescriptorFactory(), "test-key", "http://localhost", "test-model");

    @Test
    void modelToolCallsWithoutIdsAreRejected() {
        for (Object id : Arrays.asList(null, "", " ", 123)) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", id);
            call.put("function", Map.of("name", "test", "arguments", "{}"));
            var response = Map.of("choices", List.of(Map.of("message", Map.of("tool_calls", List.of(call)))));
            assertThrows(IllegalStateException.class,
                    () -> ReflectionTestUtils.invokeMethod(client, "parseDecision", response));
        }
    }

    @Test
    void failureIsReturnedToModelWithOriginalCallId() {
        var response = Map.of("choices", List.of(Map.of("message", Map.of("tool_calls", List.of(Map.of(
                "id", "model-call-1", "function", Map.of("name", "test", "arguments", "{}")))))));
        LlmDecision decision = ReflectionTestUtils.invokeMethod(client, "parseDecision", response);
        AgentState state = new AgentState("test task", List.of());
        state.addStep(new AgentStep(decision,
                new ToolFailure<>(decision.getToolCallId(), "test", "INVALID_ARGUMENT", "Required field missing")));

        List<Map<String, Object>> messages = ReflectionTestUtils.invokeMethod(client, "buildMessages", state);
        assertEquals("model-call-1", decision.getToolCallId());
        assertEquals("tool", messages.getLast().get("role"));
        assertEquals("model-call-1", messages.getLast().get("tool_call_id"));
        var content = JsonMapper.builder().build().readValue(messages.getLast().get("content").toString(), Map.class);
        assertEquals("INVALID_ARGUMENT", content.get("error_code"));
    }
}

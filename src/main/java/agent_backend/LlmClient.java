package agent_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import agent_backend.tool.AgentTool;
import agent_backend.tool.ToolInvocation;
import agent_backend.tool.ToolDescriptorFactory;
import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolResult;
import agent_backend.tool.result.ToolSuccess;
import tools.jackson.databind.ObjectMapper;

@Service
public class LlmClient {
    private static final String SYSTEM_PROMPT = """
            You are an agent. Answer the user's task directly when no tool is needed.
            Use one of the supplied tools when external execution is necessary.
            After receiving a tool result, use it to decide whether another tool call is
            required or whether you can provide the final answer.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ToolDescriptorFactory descriptorFactory;
    private final String apiKey;
    private final String model;

    public LlmClient(ObjectMapper objectMapper, ToolDescriptorFactory descriptorFactory,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.model:}") String model) {
        this.objectMapper = objectMapper;
        this.descriptorFactory = descriptorFactory;
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.create(baseUrl);
    }

    public LlmDecision decide(AgentState agentState) {
        validateConfiguration();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(agentState));

        List<Map<String, Object>> tools = buildTools(agentState.getTools());
        if (!tools.isEmpty()) {
            request.put("tools", tools);
            request.put("tool_choice", "auto");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(Map.class);

        return parseDecision(response);
    }

    private List<Map<String, Object>> buildMessages(AgentState state) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(Map.of("role", "user", "content", state.getTask()));

        for (AgentStep step : state.getSteps()) {
            LlmDecision decision = step.getDecision();
            var calls = decision.getToolCalls().stream().map(call -> Map.<String, Object>of(
                    "id", call.toolCallId(), "type", "function",
                    "function", Map.of("name", call.toolName(),
                            "arguments", writeJson(call.arguments())))).toList();
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", decision.getContent());
            assistant.put("tool_calls", calls);
            messages.add(assistant);

            for (ToolResult<?> toolResult : step.getToolResults()) {
                if (toolResult instanceof ToolSuccess<?> success) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("data", success.data());
                    messages.add(Map.of("role", "tool", "tool_call_id", success.toolCallId(),
                            "content", writeJson(payload)));
                } else if (toolResult instanceof ToolFailure<?> failure) {
                    messages.add(Map.of("role", "tool", "tool_call_id", failure.toolCallId(),
                            "content", writeJson(Map.of("error_code", failure.errorCode(),
                                    "error_message", failure.errorMessage()))));
                }
            }
        }
        return messages;
    }

    private List<Map<String, Object>> buildTools(List<AgentTool<?,?>> agentTools) {
        return agentTools.stream().map(tool -> Map.<String, Object>of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", descriptorFactory.create(tool).inputSchema())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private LlmDecision parseDecision(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("LLM returned an empty response");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("LLM response has no choices: " + response);
        }

        Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
        List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) message.get("tool_calls");

        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolInvocation> calls = new ArrayList<>();
            for (Map<String, Object> toolCall : toolCalls) {
                if (!(toolCall.get("id") instanceof String id) || id.isBlank()) {
                    throw new IllegalStateException("LLM tool call is missing a non-blank id");
                }
                Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
                String argumentsJson = (String) function.getOrDefault("arguments", "{}");
                calls.add(new ToolInvocation(id, (String) function.get("name"), readArguments(argumentsJson)));
            }
            Object content = message.get("content");
            return LlmDecision.toolCalls(calls, content == null ? null : content.toString());
        }

        Object content = message.get("content");
        if (content == null) {
            throw new IllegalStateException("LLM returned neither text nor a tool call: " + response);
        }
        return LlmDecision.finalAnswer(content.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArguments(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid tool arguments returned by LLM: " + json,
                    exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize tool arguments", exception);
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("LLM_API_KEY is not configured");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("LLM_MODEL is not configured");
        }
    }
}

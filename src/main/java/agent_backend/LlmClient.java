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
    private final String apiKey;
    private final String model;

    public LlmClient(ObjectMapper objectMapper,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${llm.model:}") String model) {
        this.objectMapper = objectMapper;
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
            Map<String, Object> function = Map.of(
                    "name", decision.getToolName(),
                    "arguments", writeJson(decision.getToolCallArguments()));
            Map<String, Object> toolCall = Map.of(
                    "id", decision.getToolCallId(),
                    "type", "function",
                    "function", function);
            messages.add(Map.of("role", "assistant", "tool_calls", List.of(toolCall)));
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", decision.getToolCallId(),
                    "content", step.getToolResult().getToolOutput()));
        }
        return messages;
    }

    private List<Map<String, Object>> buildTools(List<AgentTool> agentTools) {
        return agentTools.stream().map(tool -> Map.<String, Object>of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "additionalProperties", true))))
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
            Map<String, Object> toolCall = toolCalls.getFirst();
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String argumentsJson = (String) function.getOrDefault("arguments", "{}");
            Map<String, Object> arguments = readArguments(argumentsJson);
            return LlmDecision.toolCall(
                    (String) toolCall.get("id"),
                    (String) function.get("name"),
                    arguments);
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

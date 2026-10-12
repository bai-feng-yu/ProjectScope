package agent_backend.toolRegistry.search;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class BraveSearchClient {
    private final RestClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final boolean enabled;

    public BraveSearchClient(@Qualifier("braveRestClient") RestClient http, ObjectMapper mapper,
            @Value("${brave.api-key:}") String apiKey, @Value("${brave.enabled:false}") boolean enabled) {
        this.http = http;
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.enabled = enabled;
    }

    public WebSearchTool.Output search(String query) {
        if (!enabled || apiKey.isBlank()) return failure("SEARCH_NOT_CONFIGURED");
        if (query == null || query.isBlank() || query.length() > 600 || query.strip().split("\\s+").length > 75)
            return failure("INVALID_SEARCH_QUERY");
        try {
            return http.get().uri(builder -> builder.path("/res/v1/web/search")
                    .queryParam("q", "{query}").queryParam("count", 5).queryParam("safesearch", "strict").build(query))
                    .header("X-Subscription-Token", apiKey).header("Accept", "application/json")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            return failure(response.getStatusCode().value() == 429 ? "SEARCH_RATE_LIMITED" : "SEARCH_UNAVAILABLE");
                        // Never retain arbitrary provider payloads, headers or an unbounded response in Run history.
                        byte[] bytes = response.getBody().readNBytes(262145);
                        if (bytes.length > 262144) return failure("SEARCH_RESPONSE_TOO_LARGE");
                        var root = mapper.readTree(bytes);
                        var hits = new ArrayList<WebSearchTool.Hit>();
                        var results = root.path("web").path("results");
                        if (results.isArray()) for (var item : results) {
                            String url = item.path("url").asText("");
                            if (!safeLink(url)) continue;
                            hits.add(new WebSearchTool.Hit(limit(item.path("title").asText(""), 300), url,
                                    limit(item.path("description").asText(""), 2000)));
                            if (hits.size() == 5) break;
                        }
                        return new WebSearchTool.Output(List.copyOf(hits), null);
                    });
        } catch (Exception unavailable) {
            // Do not persist provider bodies, credential-bearing headers or exception messages.
            return failure("SEARCH_UNAVAILABLE");
        }
    }

    private static boolean safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 2048 && uri.getHost() != null && uri.getUserInfo() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String limit(String value, int length) { return value.substring(0, Math.min(value.length(), length)); }
    private static WebSearchTool.Output failure(String code) { return new WebSearchTool.Output(List.of(), code); }
}

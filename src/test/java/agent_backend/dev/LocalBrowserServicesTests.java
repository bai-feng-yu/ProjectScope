package agent_backend.dev;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.json.JsonMapper;

class LocalBrowserServicesTests {
    private final String control = "local-test-control-key-at-least-32-characters";
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> post(LocalBrowserServices service, String path, String body, String key) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(service.address() + path))
                    .header("Content-Type", "application/json").header("X-Local-Control", key)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void localIdentitiesRequireControlKeyAndHaveVerifiableSignatures() throws Exception {
        try (var service = new LocalBrowserServices(0, control, "test", true)) {
            assertEquals(403, post(service, "/token", "{\"user\":\"alice\"}", "wrong").statusCode());
            assertEquals(400, post(service, "/token", "{\"user\":\"admin\"}", control).statusCode());
            var decoder = NimbusJwtDecoder.withJwkSetUri(service.address() + "/jwks").build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(service.address()));
            for (String user : new String[]{"alice", "bob"}) {
                var response = post(service, "/token", json.writeValueAsString(Map.of("user", user)), control);
                assertEquals(200, response.statusCode());
                var jwt = decoder.decode(json.readTree(response.body()).path("access_token").asText());
                assertEquals(user, jwt.getSubject());
                assertTrue(jwt.getAudience().contains("projectscope-browser-local"));
                assertEquals(3600, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
                assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
        }
    }

    @Test void liveModeDoesNotProvideFakeModelResponses() throws Exception {
        try (var service = new LocalBrowserServices(0, control, "test", false)) {
            assertEquals(404, post(service, "/chat/completions", "{}", "").statusCode());
        }
    }

    @Test void demoModelSupportsMemoryExtractionAndClearlyLabelsAnswers() throws Exception {
        try (var service = new LocalBrowserServices(0, control, "test", true)) {
            var request = """
                    {"messages":[{"role":"system","content":"Extract memories"},
                    {"role":"user","content":"Untrusted source message: 我从事 Java 工作"}]}
                    """;
            var response = json.readTree(post(service, "/chat/completions", request, "").body());
            var memories = json.readTree(response.path("choices").path(0).path("message").path("content").asText());
            assertEquals("我从事 Java 工作", memories.path("memories").path(0).path("content").asText());
            response = json.readTree(post(service, "/chat/completions", "{\"messages\":[]}", "").body());
            assertTrue(response.path("choices").path(0).path("message").path("content").asText().contains("不是实际")
                    || response.path("choices").path(0).path("message").path("content").asText().contains("不是真实模型"));
        }
    }
}

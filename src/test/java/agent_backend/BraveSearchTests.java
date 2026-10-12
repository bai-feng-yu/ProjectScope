package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import agent_backend.toolRegistry.search.BraveSearchClient;
import tools.jackson.databind.json.JsonMapper;

class BraveSearchTests {
    @Test void searchEncodesQueryBoundsPayloadAndNeverFollowsRedirectOrRetries() throws Exception {
        var count = new AtomicInteger();
        var status = new AtomicInteger(200);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/res/v1/web/search", exchange -> {
            try (exchange) {
                count.incrementAndGet();
                assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-Subscription-Token"));
                assertTrue(exchange.getRequestURI().getRawQuery().contains("q=Java%20%26%20career"));
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/trap");
                byte[] body = """
                        {"web":{"results":[{"title":"Java","url":"https://example.com/java","description":"Career guide"},
                        {"title":"Unsafe","url":"javascript:alert(1)","description":"ignore"}]}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/trap", exchange -> { fail("Must not follow redirects"); });
        server.start();
        try (var transport = new LlmHttpConfig().llmHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            var http = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .requestFactory(new HttpComponentsClientHttpRequestFactory(transport)).build();
            var client = new BraveSearchClient(http, JsonMapper.builder().build(), "test-key", true);
            var result = client.search("Java & career");
            assertNull(result.errorCode());
            assertEquals(1, result.results().size());
            assertEquals("https://example.com/java", result.results().getFirst().url());
            status.set(429);
            assertEquals("SEARCH_RATE_LIMITED", client.search("Java & career").errorCode());
            status.set(302);
            assertEquals("SEARCH_UNAVAILABLE", client.search("Java & career").errorCode());
            assertEquals(3, count.get());
            assertEquals("SEARCH_NOT_CONFIGURED", new BraveSearchClient(http, JsonMapper.builder().build(), "", true)
                    .search("Java & career").errorCode());
            assertEquals(3, count.get());
        } finally { server.stop(0); }
    }
}

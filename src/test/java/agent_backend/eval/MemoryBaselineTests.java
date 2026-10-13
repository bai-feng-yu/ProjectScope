package agent_backend.eval;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import agent_backend.dev.LocalBrowserServices;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker=true)
class MemoryBaselineTests {
    @TempDir Path directory;
    @Test void isolatedRunnerImportsRetrievesAndWritesUngradedEvidenceWithoutProviderKeys() throws Exception {
        try (var fixture = new LocalBrowserServices(0, "evaluation-fixture-control-key-32-characters", "test", true)) {
            Path configuration = directory.resolve("model.properties");
            Files.writeString(configuration, "llm.base-url=" + fixture.address() + "\nllm.model=local-eval-fixture\nllm.api-key=fixture\n");
            Path input = directory.resolve("input.json");
            String source = Files.readString(Path.of("src/test/resources/memory-eval/smoke.json"))
                    .replace("I am a Java backend engineer. I prefer fully remote work and want to build personal AI agents.", "我从事 Java 工作，希望远程办公。");
            Files.writeString(input, source);
            Path output = directory.resolve("results");
            MemoryBaseline.main(new String[]{input.toString(), output.toString(), configuration.toString(), "10"});
            var result = JsonMapper.builder().build().readTree(Files.readString(output.resolve("case-000.json")));
            assertEquals("ANSWERED_UNSCORED", result.path("status").asText());
            assertEquals(2, result.path("importedTurns").asInt());
            assertEquals(1, result.path("confirmedCandidates").asInt());
            assertEquals("intro", result.path("retrievedSessionIds").path(0).asText());
            assertTrue(result.path("hypothesis").asText().contains("远程办公"));
            assertFalse(result.path("noMemoryHypothesis").asText().contains("远程办公"));
            assertThrows(IllegalArgumentException.class, () -> MemoryBaseline.main(
                    new String[]{input.toString(), output.toString(), configuration.toString(), "10"}));
        }
    }

    @Test void retryableProviderErrorsRecoverAndLeaveSanitizedDiagnostics() throws Exception {
        try (var fixture = new LocalBrowserServices(0, "evaluation-fixture-control-key-32-characters", "test", true);
             var client = HttpClient.newHttpClient()) {
            var calls = new AtomicInteger();
            var flaky = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            flaky.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    byte[] requestBody = exchange.getRequestBody().readAllBytes();
                    if (calls.incrementAndGet() <= 2) { exchange.sendResponseHeaders(429, -1); return; }
                    var response = client.send(HttpRequest.newBuilder(URI.create(fixture.address() + "/chat/completions"))
                            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            flaky.start();
            try {
                Path configuration = directory.resolve("flaky.properties");
                Files.writeString(configuration, "llm.base-url=http://127.0.0.1:" + flaky.getAddress().getPort()
                        + "\nllm.model=local-eval-fixture\nllm.api-key=fixture\n");
                Path output = directory.resolve("retry-results");
                MemoryBaseline.main(new String[]{"src/test/resources/memory-eval/smoke.json", output.toString(),
                        configuration.toString(), "10", "1", "30", "2"});
                var json = JsonMapper.builder().build();
                var run = json.readTree(Files.readString(output.resolve("run.json")));
                assertEquals("ANSWERED_UNSCORED", run.path("status").asText());
                assertEquals(2, run.path("providerErrors").asInt());
                assertEquals(0, run.path("providerTerminalFailures").asInt());
                String diagnostics = Files.readString(output.resolve("provider-errors.jsonl"));
                assertEquals(2, diagnostics.lines().count());
                assertTrue(diagnostics.lines().allMatch(line -> line.contains("\"httpStatus\":429") && line.contains("\"willRetry\":true")));
                assertFalse(diagnostics.contains("fixture"));
            } finally { flaky.stop(0); }
        }
    }
}

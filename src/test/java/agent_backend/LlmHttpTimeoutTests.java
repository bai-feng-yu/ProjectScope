package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.*;
import org.springframework.web.client.HttpClientErrorException;
import agent_backend.persistence.*;
import agent_backend.task.*;
import agent_backend.tool.*;
import agent_backend.toolRegistry.ToolRegistry;
import tools.jackson.databind.json.JsonMapper;

class LlmHttpTimeoutTests {
    private HttpServer server;
    private ExecutorService serverThreads;
    private CloseableHttpClient http;
    private LlmClient client;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String mode = "normal";

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                if (mode.equals("headers")) release.await(5, TimeUnit.SECONDS);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (mode.equals("body")) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{\"choices\":".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    release.await(5, TimeUnit.SECONDS);
                    return;
                }
                byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"done\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(mode.equals("rate-limit") ? 429 : 200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Expected when the client times out and closes the connection.
            }
        });
        server.start();
        var config = new LlmHttpConfig();
        http = config.llmHttpClient(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(250));
        client = new LlmClient(JsonMapper.builder().build(), new ToolDescriptorFactory(), "test",
                config.llmRestClient(http, "http://127.0.0.1:" + server.getAddress().getPort()), "test-model");
    }

    @AfterEach
    void cleanup() throws Exception {
        release.countDown();
        if (http != null) http.close();
        if (server != null) server.stop(0);
        if (serverThreads != null) serverThreads.shutdownNow();
    }

    @Test
    void normalResponseStillParses() {
        var response = client.request(new AgentState("hello"));
        assertEquals("done", client.parseDecision(response).getFinalAnswer());
        assertEquals(1, requests.get());
    }

    @Test
    void delayedHeadersTimeoutWithoutRetry() {
        mode = "headers";
        assertTimeout(Duration.ofSeconds(4), () -> {
            var error = assertThrows(LlmTimeoutException.class, () -> client.request(new AgentState("hello")));
            assertEquals(LlmTimeoutException.Phase.RESPONSE_READ, error.phase());
        });
        assertEquals(1, requests.get());
    }

    @Test
    void stalledBodyTimeoutFailsTaskAndStepWithoutExecutingTools() {
        mode = "body";
        var tasks = mock(TaskStore.class);
        var steps = mock(StepStore.class);
        var calls = mock(ToolInvocationStore.class);
        var executor = mock(ToolBatchExecutor.class);
        var registry = mock(ToolRegistry.class);
        when(registry.getAllTools()).thenReturn(List.of());
        var leases = mock(TaskLeaseManager.class);
        var session = mock(LeaseSession.class);
        var lease = new TaskLease("test-task", "test-owner");
        when(session.lease()).thenReturn(lease);
        when(leases.acquire(anyString())).thenReturn(session);
        var runner = new AgentRunner(client, executor, registry, tasks, steps, calls, leases, 3);
        assertTimeout(Duration.ofSeconds(4), () -> runner.run("hello"));
        verify(tasks).fail(eq(lease), contains("LLM_TIMEOUT: RESPONSE_READ"));
        verify(steps).fail(eq(lease), anyString(), contains("LLM_TIMEOUT: RESPONSE_READ"));
        verify(steps, never()).saveResponse(any(), anyString(), any());
        verifyNoInteractions(executor);
        verify(session).close();
        assertEquals(1, requests.get());
    }

    @Test
    void rateLimitRemainsHttpErrorAndIsNotRetried() {
        mode = "rate-limit";
        assertThrows(HttpClientErrorException.TooManyRequests.class,
                () -> client.request(new AgentState("hello")));
        assertEquals(1, requests.get());
    }

    @Test
    void poolAndConnectTimeoutsAreClassifiedWithoutMatchingErrorText() {
        var errors = List.of(
                new org.apache.hc.core5.http.ConnectionRequestTimeoutException("test"),
                new org.apache.hc.client5.http.ConnectTimeoutException("test"));
        var phases = List.of(LlmTimeoutException.Phase.CONNECTION_POOL, LlmTimeoutException.Phase.CONNECT);
        for (int i = 0; i < errors.size(); i++) {
            var error = errors.get(i);
            var rest = org.springframework.web.client.RestClient.builder().baseUrl("http://localhost")
                    .requestFactory((uri, method) -> { throw error; }).build();
            var testClient = new LlmClient(JsonMapper.builder().build(), new ToolDescriptorFactory(),
                    "test", rest, "model");
            var result = assertThrows(LlmTimeoutException.class, () -> testClient.request(new AgentState("hello")));
            assertEquals(phases.get(i), result.phase());
        }
    }

    @Test
    void zeroAndNegativeTimeoutsAreRejected() {
        var config = new LlmHttpConfig();
        assertThrows(IllegalArgumentException.class,
                () -> config.llmHttpClient(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> config.llmHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofSeconds(1)));
    }
}

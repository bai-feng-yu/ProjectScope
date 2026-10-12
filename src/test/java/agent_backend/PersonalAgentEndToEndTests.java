package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL, scheduler, HTTP model protocol and search adapter; providers are deterministic local fixtures. */
@SpringBootTest(properties={"agent.scheduler.poll-interval-ms=100", "brave.enabled=true", "brave.api-key=fixture-key"})
@Testcontainers(disabledWithoutDocker=true)
class PersonalAgentEndToEndTests {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    static final JsonMapper json = JsonMapper.builder().build();
    static final AtomicBoolean recalled = new AtomicBoolean();
    static final AtomicInteger searches = new AtomicInteger();
    static final HttpServer provider;
    static {
        try {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = json.readTree(exchange.getRequestBody().readAllBytes());
                    var messages = request.path("messages");
                    String system = messages.get(0).path("content").asText("");
                    Object message;
                    String finish = "stop";
                    if (request.has("tools")) {
                        boolean hasToolResult = false;
                        for (var item : messages) if ("tool".equals(item.path("role").asText())) hasToolResult = true;
                        if (hasToolResult) message = Map.of("content", "Java Agent roles fit your goals. Source: https://example.com/career");
                        else {
                            finish = "tool_calls";
                            message = Map.of("tool_calls", List.of(Map.of("id", "search-1", "type", "function",
                                    "function", Map.of("name", "web_search", "arguments", "{\"query\":\"Java Agent career skills\"}"))));
                        }
                    } else if (system.startsWith("Extract")) {
                        message = Map.of("content", "{\"memories\":[{\"topic\":\"career\",\"kind\":\"PREFERENCE\",\"content\":\"Prefers remote Java work\"}]}");
                    } else {
                        if (system.contains("Prefers remote Java work")) recalled.set(true);
                        message = Map.of("content", "Let's compare options for your career.");
                    }
                    byte[] body = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", finish, "message", message))));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            provider.createContext("/res/v1/web/search", exchange -> {
                try (exchange) {
                    searches.incrementAndGet();
                    byte[] body = "{\"web\":{\"results\":[{\"title\":\"Career guide\",\"url\":\"https://example.com/career\",\"description\":\"Java Agent roles\"}]}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            provider.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("llm.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort());
        registry.add("llm.model", () -> "fixture-model");
        registry.add("llm.api-key", () -> "fixture-key");
    }
    @TestBean(methodName="searchHttp") RestClient braveRestClient;
    static RestClient searchHttp() { return RestClient.builder().baseUrl("http://127.0.0.1:" + provider.getAddress().getPort()).build(); }
    @AfterAll static void close() { provider.stop(0); }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private RequestPostProcessor identity(String name) {
        return jwt().jwt(token -> token.subject(name).issuer("https://identity.example.test").expiresAt(Instant.now().plusSeconds(300)));
    }
    private String createConversation(String title) throws Exception {
        var result = mvc.perform(post("/conversations").with(identity("alice")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title)))).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("conversationId").asText();
    }

    @Test void careerConversationMemoryResearchEventsAndWeeklyAssistanceFormOneLoop() throws Exception {
        String first = createConversation("Career");
        mvc.perform(post("/conversations/{id}/messages", first).with(identity("alice")).header("Idempotency-Key", "intro")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"I prefer remote Java work\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        var memories = mvc.perform(get("/memories").with(identity("alice"))).andExpect(status().isOk()).andReturn();
        var candidate = json.readTree(memories.getResponse().getContentAsString()).get(0);
        mvc.perform(post("/memories/{id}/confirm", candidate.path("memoryId").asText()).with(identity("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"replaceExisting\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        String second = createConversation("Next session");
        mvc.perform(post("/conversations/{id}/messages", second).with(identity("alice")).header("Idempotency-Key", "recall")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"What career options fit me?\"}"))
                .andExpect(status().isOk());
        assertTrue(recalled.get(), "Confirmed memory must reach the model in a new conversation");
        var submitted = mvc.perform(post("/conversations/{id}/research", second).with(identity("alice"))
                .header("Idempotency-Key", "research").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Research Java Agent roles\"}"))
                .andExpect(status().isAccepted()).andReturn();
        var submission = json.readTree(submitted.getResponse().getContentAsString());
        String run = submission.path("runId").asText(), message = submission.path("messageId").asText();
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            while (jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE reply_to=?", Integer.class, message) == 0)
                Thread.sleep(50);
        });
        assertEquals(1, searches.get());
        mvc.perform(get("/runs/{id}", run).with(identity("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/runs/{id}/steps", run).with(identity("alice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        var events = mvc.perform(get("/runs/{id}/events", run).with(identity("alice")))
                .andExpect(request().asyncStarted()).andReturn();
        String stream = mvc.perform(asyncDispatch(events)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(stream.contains("COMPLETED"));
        mvc.perform(get("/conversations/{id}/messages", second).with(identity("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/runs/{id}", run).with(identity("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/memories").with(identity("bob"))).andExpect(jsonPath("$.length()").value(0));
        var scheduled = mvc.perform(post("/schedules").with(identity("alice")).header("Idempotency-Key", "weekly")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("conversationId", second,
                        "content", "Weekly career research", "firstFireAt", Instant.now().plusSeconds(3600).toString()))))
                .andExpect(status().isCreated()).andReturn();
        String schedule = json.readTree(scheduled.getResponse().getContentAsString()).path("scheduleId").asText();
        mvc.perform(put("/schedules/{id}/pause", schedule).with(identity("alice")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paused\":true}")).andExpect(status().isOk());
        mvc.perform(put("/schedules/{id}/pause", schedule).with(identity("bob")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paused\":false}")).andExpect(status().isNotFound());
        mvc.perform(get("/schedules").with(identity("alice"))).andExpect(jsonPath("$[0].paused").value(true));
    }
}

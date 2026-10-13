package agent_backend.eval;

import agent_backend.AgentBackendApplication;
import agent_backend.personal.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in benchmark executable, never a production endpoint or an ordinary Maven test. */
public final class MemoryBaseline {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: input.json output-directory local-properties max-model-requests");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        Files.createDirectories(output);
        if (Files.exists(output.resolve("run.json"))) throw new IllegalArgumentException("Use a new output directory; existing evidence is never overwritten");
        var configuration = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[2]))) { configuration.load(reader); }
        String base = setting(configuration, "llm.base-url", "LLM_BASE_URL");
        String model = setting(configuration, "llm.model", "LLM_MODEL");
        String key = setting(configuration, "llm.api-key", "LLM_API_KEY");
        var cases = JSON.readTree(Files.readString(input));
        if (!cases.isArray() || cases.isEmpty()) throw new IllegalArgumentException("Non-empty case array required");
        int maxCalls = Integer.parseInt(args[3]);
        if (maxCalls < 1) throw new IllegalArgumentException("Positive call limit required");
        try (var proxy = new MeteredProvider(base, key, maxCalls, output.resolve("extraction-cache"));
             var mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            // Only model properties come from the local file. Never connect to its database or identity service.
            try (var app = new SpringApplicationBuilder(AgentBackendApplication.class).run(
                    "--spring.config.location=classpath:/application.properties", "--spring.profiles.active=memory-eval",
                    "--server.port=0", "--server.address=127.0.0.1", "--agent.scheduler.enabled=false",
                    "--brave.enabled=false", "--debug=false", "--logging.level.root=WARN", "--logging.level.org.springframework=WARN", "--logging.level.agent_backend=WARN",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(), "--spring.datasource.username=" + mysql.getUsername(),
                    "--spring.datasource.password=" + mysql.getPassword(), "--llm.base-url=" + proxy.address(),
                    "--llm.api-key=local-metered-proxy", "--llm.model=" + model)) {
                var metadata = new LinkedHashMap<String,Object>();
                metadata.put("startedAt", Instant.now().toString()); metadata.put("model", model);
                metadata.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
                metadata.put("cases", cases.size()); metadata.put("maxModelRequests", maxCalls);
                metadata.put("protocol", "original-history-import/user-only-extraction/auto-confirm-replace/new-conversation-answer/v1");
                metadata.put("extractionCache", "Identical extraction request bodies reuse the first successful response within this run; disk artifacts retained for audit");
                metadata.put("limitations", List.of("test-only history import, not public HTTP ingestion", "source dates not passed to extraction",
                        "assistant history stored but not extracted", "auto-confirm is a simulated user, not autonomous memory", "no automatic forget instruction handler"));
                write(output.resolve("run.json"), metadata);
                var conversations = app.getBean(ConversationStore.class);
                var memory = app.getBean(MemoryService.class);
                var store = app.getBean(MemoryStore.class);
                var chat = app.getBean(ConversationService.class);
                var jdbc = app.getBean(JdbcTemplate.class);
                var pool = Executors.newFixedThreadPool(4);
                var failedCases = new AtomicInteger();
                try {
                    var work = new ArrayList<Future<?>>();
                    int index = 0;
                    for (var item : cases) {
                        final int number = index++;
                        work.add(pool.submit(() -> {
                            var result = new LinkedHashMap<String,Object>();
                            result.put("question_id", item.path("id").asText()); result.put("category", item.path("category").asText());
                            long start = System.nanoTime();
                            try {
                                var jwt = Jwt.withTokenValue("evaluation-only").header("alg", "none").issuer("urn:projectscope:isolated-eval")
                                        .subject(UUID.randomUUID().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(86400)).build();
                                SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
                                String user = agent_backend.security.TaskIdentity.current().userId();
                                var sourceSessions = new HashMap<String,String>();
                                int imported = 0, extracted = 0, confirmed = 0;
                                for (var session : item.path("sessions")) {
                                    String conversation = conversations.create(user, "Benchmark history");
                                    for (var turn : session.path("turns")) {
                                        if (proxy.exhausted()) throw new IllegalStateException("Provider budget or provider failure circuit opened");
                                        String role = turn.path("role").asText().toUpperCase(Locale.ROOT);
                                        if (!Set.of("USER", "ASSISTANT", "SYSTEM").contains(role)) throw new IllegalArgumentException("Unknown history role");
                                        // Dataset system/profile metadata is not user testimony and never extracted.
                                        if (role.equals("SYSTEM")) continue;
                                        String source = UUID.randomUUID().toString();
                                        jdbc.update("INSERT INTO conversation_message(message_id,user_id,conversation_id,role,content,status) VALUES (?,?,?,?,?,'COMPLETED')",
                                                source, user, conversation, role, turn.path("content").asText());
                                        sourceSessions.put(source, session.path("id").asText()); imported++;
                                        if (role.equals("USER")) {
                                            memory.extract(user, source, turn.path("content").asText()); extracted++;
                                            // No gold labels or question are consulted when simulating confirmation.
                                            var candidates = jdbc.query("SELECT * FROM personal_memory WHERE user_id=? AND source_message_id=? AND status='CANDIDATE' ORDER BY updated_at,memory_id",
                                                    org.springframework.jdbc.core.DataClassRowMapper.newInstance(MemoryStore.Memory.class), user, source);
                                            for (var candidate : candidates) { store.confirm(user, candidate.memoryId(), candidate.version(), true); confirmed++; }
                                        }
                                    }
                                }
                                String question = item.path("question").asText();
                                String prompt = item.path("questionDate").asText().isBlank() ? question
                                        : "Current question date: " + item.path("questionDate").asText() + "\n" + question;
                                var retrieved = memory.relevant(user, prompt);
                                result.put("importedTurns", imported); result.put("extractionAttempts", extracted); result.put("confirmedCandidates", confirmed);
                                result.put("activeCount", jdbc.queryForObject("SELECT COUNT(*) FROM personal_memory WHERE user_id=? AND status='ACTIVE'", Integer.class, user));
                                result.put("retrieved", retrieved);
                                result.put("retrievedSessionIds", retrieved.stream().map(m -> sourceSessions.get(m.sourceMessageId())).filter(Objects::nonNull).distinct().toList());
                                String conversation = conversations.create(user, "Benchmark question (no historical messages)");
                                var answers = chat.send(conversation, UUID.randomUUID().toString(), prompt);
                                var answer = answers.stream().filter(m -> m.role().equals("ASSISTANT")).findFirst().orElseThrow();
                                result.put("hypothesis", answer.content());
                                // Paired ablation uses an empty conversation and disables both retrieval and extraction.
                                store.settings(user, false);
                                var control = chat.send(conversations.create(user, "No-memory control"), UUID.randomUUID().toString(), prompt);
                                result.put("noMemoryHypothesis", control.stream().filter(m -> m.role().equals("ASSISTANT")).findFirst().orElseThrow().content());
                                result.put("status", "ANSWERED_UNSCORED");
                            } catch (Exception failure) {
                                failedCases.incrementAndGet();
                                result.put("status", "ERROR"); result.put("errorClass", failure.getClass().getSimpleName());
                            } finally {
                                SecurityContextHolder.clearContext();
                                result.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                                try { write(output.resolve(String.format("case-%03d.json", number)), result); }
                                catch (IOException failure) { throw new UncheckedIOException(failure); }
                                System.out.println("Case " + number + ": " + result.get("status"));
                            }
                        }));
                    }
                    for (var future : work) future.get();
                } finally { pool.shutdownNow(); }
                metadata.put("finishedAt", Instant.now().toString()); metadata.put("providerRequests", proxy.requests.get());
                metadata.put("providerErrors", proxy.errors.get());
                metadata.put("extractionCacheHits", proxy.cacheHits.get());
                metadata.put("invalidExtractionResponses", proxy.invalidExtractions.get());
                metadata.put("responsesWithUsage", proxy.responsesWithUsage.get());
                metadata.put("reportedPromptTokens", proxy.promptTokens.get());
                metadata.put("reportedCompletionTokens", proxy.completionTokens.get());
                metadata.put("failedCases", failedCases.get());
                metadata.put("status", failedCases.get() == 0 ? "ANSWERED_UNSCORED" : "INCOMPLETE");
                write(output.resolve("run.json"), metadata);
                if (failedCases.get() != 0) throw new IllegalStateException("Some cases failed; inspect per-case artifacts");
            }
        }
    }

    static String setting(Properties config, String property, String environment) {
        String value = config.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        if (value == null || value.isBlank() || value.contains("${")) throw new IllegalArgumentException("Missing literal configuration: " + property);
        return value.trim();
    }

    private static void write(Path path, Object value) throws IOException { Files.writeString(path, JSON.writeValueAsString(value)); }

    /** Local forwarding meter: stops after repeated provider errors; no retries, credentials or bodies in logs. */
    private static final class MeteredProvider implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger(), errors = new AtomicInteger();
        final AtomicInteger cacheHits = new AtomicInteger();
        final AtomicInteger invalidExtractions = new AtomicInteger(), responsesWithUsage = new AtomicInteger();
        final AtomicLong promptTokens = new AtomicLong(), completionTokens = new AtomicLong();
        final ConcurrentHashMap<String,CompletableFuture<byte[]>> cache = new ConcurrentHashMap<>();
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        final int limit;
        MeteredProvider(String base, String key, int limit, Path cacheDirectory) throws IOException {
            this.limit = limit;
            Files.createDirectories(cacheDirectory);
            URI target = URI.create(base.replaceAll("/+$", "") + "/chat/completions");
            if (!target.getScheme().equals("https") && !Set.of("127.0.0.1", "localhost").contains(target.getHost()))
                throw new IllegalArgumentException("Remote model endpoint must use HTTPS");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    var parsed = JSON.readTree(body);
                    String cacheKey = parsed.path("messages").path(0).path("content").asText().startsWith("Extract")
                            ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)) : null;
                    var pending = new CompletableFuture<byte[]>();
                    var prior = cacheKey == null ? null : cache.putIfAbsent(cacheKey, pending);
                    if (prior != null) {
                        cacheHits.incrementAndGet();
                        byte[] cached = prior.get(150, TimeUnit.SECONDS);
                        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                        exchange.sendResponseHeaders(200, cached.length); exchange.getResponseBody().write(cached); return;
                    }
                    try {
                    if (exhausted()) { exchange.sendResponseHeaders(429, -1); return; }
                    int number = requests.incrementAndGet();
                    if (number > limit) { exchange.sendResponseHeaders(429, -1); return; }
                    if (number % 25 == 0) System.out.println("Provider requests started: " + number);
                    var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(120))
                            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() >= 400) errors.incrementAndGet();
                    if (response.statusCode() == 200) {
                        try {
                            var responseJson = JSON.readTree(response.body());
                            var usage = responseJson.path("usage");
                            if (usage.path("prompt_tokens").isNumber() && usage.path("completion_tokens").isNumber()) {
                                responsesWithUsage.incrementAndGet();
                                promptTokens.addAndGet(usage.path("prompt_tokens").asLong());
                                completionTokens.addAndGet(usage.path("completion_tokens").asLong());
                            }
                            if (cacheKey != null) {
                                var memories = JSON.readTree(responseJson.path("choices").path(0).path("message").path("content").asText()).path("memories");
                                if (!memories.isArray() || memories.size() > 3) invalidExtractions.incrementAndGet();
                            }
                        } catch (Exception invalidResponse) { if (cacheKey != null) invalidExtractions.incrementAndGet(); }
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                    if (cacheKey != null && response.statusCode() == 200) {
                        Files.write(cacheDirectory.resolve(cacheKey + ".json"), response.body());
                        pending.complete(response.body());
                    }
                    } finally {
                        if (!pending.isDone()) pending.completeExceptionally(new IOException("Extraction request unavailable"));
                    }
                } catch (Exception failure) { errors.incrementAndGet(); }
            });
            server.start();
        }
        boolean exhausted() { return requests.get() >= limit || errors.get() >= 3; }
        String address() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); executor.shutdownNow(); client.close(); }
    }
}

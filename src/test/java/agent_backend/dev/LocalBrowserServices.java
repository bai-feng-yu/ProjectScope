package agent_backend.dev;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local launcher fixture only: compiled into test-classes, never included in the production jar. */
public final class LocalBrowserServices implements AutoCloseable {
    private final JsonMapper json = JsonMapper.builder().build();
    private final java.security.KeyPair key;
    private final String keyId = UUID.randomUUID().toString();
    private final String controlKey;
    private final String session;
    private final boolean demo;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalBrowserServices(int port, String controlKey, String session, boolean demo) throws Exception {
        if (controlKey == null || controlKey.length() < 32) throw new IllegalArgumentException("Local control key is required");
        this.controlKey = controlKey;
        this.session = session;
        this.demo = demo;
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public String address() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            // No CORS. Only the launcher with the random control key may mint identities.
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 200, Map.of("service", "projectscope-local-browser", "session", session, "demo", demo));
            } else if (path.equals("/jwks") && exchange.getRequestMethod().equals("GET")) {
                var publicKey = (RSAPublicKey) key.getPublic();
                reply(exchange, 200, Map.of("keys", List.of(Map.of("kty", "RSA", "use", "sig", "alg", "RS256", "kid", keyId,
                        "n", unsigned(publicKey.getModulus()), "e", unsigned(publicKey.getPublicExponent())))));
            } else if (path.equals("/token") && exchange.getRequestMethod().equals("POST")) {
                if (!java.security.MessageDigest.isEqual(controlKey.getBytes(StandardCharsets.UTF_8),
                        Objects.toString(exchange.getRequestHeaders().getFirst("X-Local-Control"), "").getBytes(StandardCharsets.UTF_8))) {
                    reply(exchange, 403, Map.of("error", "local_control_required")); return;
                }
                String user = body(exchange).path("user").asText("");
                if (!List.of("alice", "bob").contains(user)) { reply(exchange, 400, Map.of("error", "test_user_required")); return; }
                reply(exchange, 200, Map.of("access_token", token(user), "expires_in", 3600));
            } else if (demo && path.equals("/chat/completions") && exchange.getRequestMethod().equals("POST")) {
                model(exchange, body(exchange));
            } else reply(exchange, 404, Map.of("error", "not_found"));
        } catch (Exception failure) {
            // Never log request bodies, tokens, headers or provider configuration.
            System.err.println("Local fixture request could not be completed: " + failure.getClass().getSimpleName());
        }
    }

    private JsonNode body(HttpExchange exchange) throws java.io.IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(262145);
        if (bytes.length > 262144) throw new IllegalArgumentException("Fixture request too large");
        return json.readTree(bytes);
    }

    private String token(String user) throws Exception {
        long now = Instant.now().getEpochSecond();
        String unsigned = encoded(json.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", keyId))) + "."
                + encoded(json.writeValueAsBytes(Map.of("iss", address(), "aud", "projectscope-browser-local", "sub", user,
                        "iat", now, "exp", now + 3600)));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + encoded(signature.sign());
    }

    private void model(HttpExchange exchange, JsonNode request) throws Exception {
        var messages = request.path("messages");
        String system = messages.path(0).path("content").asText("");
        String source = messages.path(messages.size() - 1).path("content").asText("");
        Object message;
        String finish = "stop";
        if (request.has("tools")) {
            boolean hasResult = false;
            for (var item : messages) if ("tool".equals(item.path("role").asText())) hasResult = true;
            Thread.sleep(2000); // Make progress and cancellation observable without spending provider credits.
            if (!hasResult) {
                finish = "tool_calls";
                message = Map.of("tool_calls", List.of(Map.of("id", "demo-search", "type", "function", "function",
                        Map.of("name", "web_search", "arguments", "{\"query\":\"Java Agent career skills\"}"))));
            } else message = Map.of("content", "【本地演示：非真实研究】后台运行和工具调用已完成。此模式禁用 Brave，搜索应返回 SEARCH_NOT_CONFIGURED。没有访问互联网或生成真实资料引用。请用 Live 模式验证真实研究质量。");
        } else if (system.startsWith("Extract")) {
            source = source.replaceFirst("^Untrusted source message:\\s*", "");
            var candidates = source.contains("我") && (source.contains("Java") || source.contains("工作") || source.contains("偏好"))
                    ? List.of(Map.of("topic", "career", "kind", "FACT", "content", source.substring(0, Math.min(source.length(), 600))))
                    : List.of();
            message = Map.of("content", json.writeValueAsString(Map.of("memories", candidates)));
        } else {
            String marker = "Confirmed memory JSON:\n";
            String memory = system.contains(marker) ? system.substring(system.indexOf(marker) + marker.length()) : "[]";
            var facts = new ArrayList<String>();
            for (var item : json.readTree(memory)) facts.add(item.path("content").asText(""));
            message = Map.of("content", "【本地演示回复，不是真实模型】消息已保存。"
                    + (facts.isEmpty() ? "当前没有可用的已确认记忆。请描述自己的工作背景，在右侧确认候选，再新建会话测试。"
                    : "本次回答收到的已确认背景：\n" + String.join("\n", facts)));
        }
        reply(exchange, 200, Map.of("choices", List.of(Map.of("finish_reason", finish, "message", message))));
    }

    private void reply(HttpExchange exchange, int status, Object value) throws java.io.IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    private static String unsigned(java.math.BigInteger number) {
        byte[] bytes = number.toByteArray();
        return encoded(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    private static String encoded(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }

    public static void main(String[] args) throws Exception {
        var service = new LocalBrowserServices(Integer.parseInt(args[0]), System.getenv("LOCAL_BROWSER_CONTROL_KEY"),
                System.getenv("LOCAL_BROWSER_SESSION"), Boolean.parseBoolean(System.getenv("LOCAL_BROWSER_DEMO")));
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
        System.out.println("Local browser fixture ready (loopback only, test users, no production authentication bypass).");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}

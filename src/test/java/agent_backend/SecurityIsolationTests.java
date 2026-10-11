package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.sun.net.httpserver.HttpServer;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import agent_backend.persistence.*;
import agent_backend.persistence.model.*;
import agent_backend.security.TaskIdentity;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real signature verification and Spring Security filter chain; no external identity provider required. */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "spring.flyway.enabled=false", "agent.scheduler.enabled=false"})
class SecurityIsolationTests {
    private static final String ISSUER = "https://identity.example.test";
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add("agent.security.issuer", () -> ISSUER);
        registry.add("agent.security.audience", () -> "agent-backend");
        registry.add("agent.security.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stop() { JWKS.stop(0); }
    @MockitoBean TaskStore tasks;
    @MockitoBean StepStore steps;
    @MockitoBean ToolInvocationStore calls;
    @MockitoBean agent_backend.persistence.jdbc.JdbcLeaseGuard guard;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper mapper;
    private MockMvc mvc;
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        doAnswer(call -> { owners.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(tasks).createOwned(anyString(), anyString(), anyString());
        when(tasks.findOwned(anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return Objects.equals(owners.get(id), call.getArgument(1))
                    ? Optional.of(new TaskRecord(id, "private task", TaskStatus.COMPLETED, "private answer", null,
                            null, null, null, null, null, null, null)) : Optional.empty();
        });
    }

    private String token(String subject) throws Exception {
        return token(KEY, ISSUER, "agent-backend", subject, Instant.now().plusSeconds(300));
    }
    private String token(RSAKey key, String issuer, String audience, String subject, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject);
        if (expires != null) claims.expirationTime(Date.from(expires));
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test void rejectsAnonymousForgedExpiredAndWrongClaims() throws Exception {
        String path = "/tasks/" + UUID.randomUUID();
        mvc.perform(get(path).header("X-User-Id", "alice")).andExpect(status().isUnauthorized());
        var wrongKey = new RSAKeyGenerator(2048).generate();
        for (String invalid : List.of("not-a-jwt",
                token(wrongKey, ISSUER, "agent-backend", "alice", Instant.now().plusSeconds(300)),
                token(KEY, "https://wrong.example", "agent-backend", "alice", Instant.now().plusSeconds(300)),
                token(KEY, ISSUER, "wrong-audience", "alice", Instant.now().plusSeconds(300)),
                token(KEY, ISSUER, "agent-backend", "alice", Instant.now().minusSeconds(120)),
                token(KEY, ISSUER, "agent-backend", null, Instant.now().plusSeconds(300)),
                token(KEY, ISSUER, "agent-backend", "alice", null))) {
            mvc.perform(get(path).header("Authorization", "Bearer " + invalid)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(tasks, steps, calls);
    }

    @Test void ownerCanReadButAnotherUserCannotReadDetailsStepsOrStream() throws Exception {
        String alice = "Bearer " + token("alice");
        String bob = "Bearer " + token("bob");
        String expectedOwner = TestIdentity.login("alice").userId();
        TestIdentity.clear();
        var response = mvc.perform(post("/tasks").header("Authorization", alice)
                .header("X-User-Id", "bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":\"private task\",\"userId\":\"bob\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsString()).get("taskId").asText();
        assertEquals(expectedOwner, owners.get(id));
        mvc.perform(get("/tasks/" + id).header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.finalAnswer").value("private answer"));
        mvc.perform(get("/tasks/" + id + "/steps").header("Authorization", alice)).andExpect(status().isOk());
        var stream = mvc.perform(get("/tasks/" + id + "/events").header("Authorization", alice))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        clearInvocations(steps, calls);
        for (String prefix : List.of("/tasks/", "/runs/")) {
          for (String suffix : List.of("", "/steps", "/events", "/attempts")) {
            mvc.perform(get(prefix + id + suffix).header("Authorization", bob))
                    .andExpect(status().isNotFound()).andExpect(request().asyncNotStarted());
            mvc.perform(get(prefix + UUID.randomUUID() + suffix).header("Authorization", bob))
                    .andExpect(status().isNotFound());
          }
        }
        verifyNoInteractions(steps, calls);
        verify(tasks, never()).find(anyString());
    }
}

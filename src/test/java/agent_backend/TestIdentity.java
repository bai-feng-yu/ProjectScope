package agent_backend;

import java.time.Instant;
import java.util.List;
import agent_backend.security.TaskIdentity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class TestIdentity {
    public static TaskIdentity login(String subject) {
        var jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .issuer("https://identity.example.test").subject(subject)
                .audience(List.of("agent-backend")).expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        return TaskIdentity.current();
    }
    public static void clear() { SecurityContextHolder.clearContext(); }
}

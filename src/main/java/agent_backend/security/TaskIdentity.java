package agent_backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** Immutable, verified request identity; explicitly carried across SSE threads. */
public record TaskIdentity(String userId, Instant expiresAt) {
    public static TaskIdentity current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        var jwt = token.getToken();
        String issuer = jwt.getClaimAsString("iss");
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()
                || jwt.getExpiresAt() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid identity");
        }
        // Length-prefix encoding avoids collisions between differently partitioned issuer/subject pairs.
        String key = issuer.length() + ":" + issuer + subject.length() + ":" + subject;
        try {
            var identity = new TaskIdentity(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8))), jwt.getExpiresAt());
            identity.requireValid();
            return identity;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void requireValid() {
        if (userId == null || userId.isBlank() || expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identity expired");
        }
    }
}

package agent_backend.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class RequestKeys {
    private RequestKeys() {}
    public static String validate(String key) {
        if (key == null || key.isBlank() || key.length() > 200)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1-200 characters");
        return key;
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

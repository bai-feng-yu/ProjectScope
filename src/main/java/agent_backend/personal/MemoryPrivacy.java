package agent_backend.personal;

import java.util.regex.Pattern;

/** Conservative deterministic guard in addition to the extraction prompt; not a general secret scanner. */
final class MemoryPrivacy {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(?:password|passwd|api[_ -]?key|access[_ -]?token|密码|密钥|令牌)\\s*[:=：]\\s*\\S+"
            + "|\\bBearer\\s+[A-Za-z0-9._~-]+|-----BEGIN [A-Z ]*PRIVATE KEY-----"
            + "|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
            + "|\\bsk-[A-Za-z0-9_-]{16,}");
    private MemoryPrivacy() {}
    static boolean containsCredential(String text) { return text != null && CREDENTIAL.matcher(text).find(); }
}

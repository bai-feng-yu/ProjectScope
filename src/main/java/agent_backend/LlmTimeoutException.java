package agent_backend;

/** Distinguishes transport waiting limits from task deadlines and model protocol errors. */
public class LlmTimeoutException extends RuntimeException {
    public enum Phase { CONNECTION_POOL, CONNECT, RESPONSE_READ }
    private final Phase phase;

    public LlmTimeoutException(Phase phase, Throwable cause) {
        super("LLM_TIMEOUT: " + phase, cause);
        this.phase = phase;
    }

    public Phase phase() { return phase; }
}

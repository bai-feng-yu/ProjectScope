package agent_backend.tool;

/** A tool can throw this when converted arguments fail its own validation. */
public class InvalidToolArgumentException extends IllegalArgumentException {
    public InvalidToolArgumentException(String message) {
        super(message);
    }
}

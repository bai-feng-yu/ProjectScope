package agent_backend.persistence;

public class LeaseLostException extends IllegalStateException {
    public LeaseLostException(String taskId) {
        super("Task lease is no longer valid: " + taskId);
    }
}

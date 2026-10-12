package agent_backend.persistence.model;

public enum TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}

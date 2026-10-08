package agent_backend.persistence.model;

public enum TaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}

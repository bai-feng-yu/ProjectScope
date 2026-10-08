package agent_backend.persistence.model;

public enum StepStatus {
    RUNNING, COMPLETED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}

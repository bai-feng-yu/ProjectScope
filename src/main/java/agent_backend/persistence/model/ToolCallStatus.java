package agent_backend.persistence.model;

public enum ToolCallStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN;

    /** UNKNOWN must not be automatically retried: the tool may have produced side effects. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == UNKNOWN;
    }
}

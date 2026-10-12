package agent_backend.tool;

/** NOT_EXECUTED requires authoritative proof, not an eventually-consistent "not found". */
public record Reconciliation(Outcome outcome, Object output, String evidence) {
    public enum Outcome { SUCCEEDED, NOT_EXECUTED, IN_PROGRESS, INDETERMINATE }
    public Reconciliation {
        org.springframework.util.Assert.notNull(outcome, "outcome is required");
        org.springframework.util.Assert.hasText(evidence, "evidence is required");
        org.springframework.util.Assert.isTrue(evidence.length() <= 2000, "evidence too long");
    }
}

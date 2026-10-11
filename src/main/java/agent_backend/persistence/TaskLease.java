package agent_backend.persistence;

import org.springframework.util.Assert;

/** A unique execution owner, generated afresh for every claim. */
public record TaskLease(String taskId, String workerId, String attemptId, long fenceToken) {
    /** Compatibility for first-attempt internal callers. Worker IDs were formerly unique per claim. */
    public TaskLease(String taskId, String workerId) {
        this(taskId, workerId, workerId, 1);
    }
    public TaskLease {
        Assert.hasText(taskId, "taskId must not be blank");
        Assert.hasText(workerId, "workerId must not be blank");
        Assert.hasText(attemptId, "attemptId must not be blank");
        Assert.isTrue(fenceToken > 0, "fenceToken must be positive");
    }
}

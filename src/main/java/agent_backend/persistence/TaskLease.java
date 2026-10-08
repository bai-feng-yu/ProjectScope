package agent_backend.persistence;

import org.springframework.util.Assert;

/** A unique execution owner, generated afresh for every claim. */
public record TaskLease(String taskId, String workerId) {
    public TaskLease {
        Assert.hasText(taskId, "taskId must not be blank");
        Assert.hasText(workerId, "workerId must not be blank");
    }
}

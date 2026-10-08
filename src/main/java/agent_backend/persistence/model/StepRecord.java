package agent_backend.persistence.model;

import java.time.LocalDateTime;

/** One model turn. modelResponse is serialized JSON; timestamps are UTC. */
public record StepRecord(
        String stepId, String taskId, int stepNumber, StepStatus status,
        String modelResponse, String failureReason,
        LocalDateTime createdAt, LocalDateTime finishedAt, LocalDateTime updatedAt) {
}

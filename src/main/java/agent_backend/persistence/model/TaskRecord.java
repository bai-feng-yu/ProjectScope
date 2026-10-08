package agent_backend.persistence.model;

import java.time.LocalDateTime;

/** Database snapshot; all timestamps are UTC. */
public record TaskRecord(
        String taskId, String taskInput, TaskStatus status,
        String finalAnswer, String failureReason,
        LocalDateTime createdAt, LocalDateTime startedAt,
        LocalDateTime finishedAt, LocalDateTime updatedAt) {
}

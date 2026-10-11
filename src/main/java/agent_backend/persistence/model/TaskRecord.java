package agent_backend.persistence.model;

import java.time.LocalDateTime;

/** Database snapshot; all timestamps are UTC. */
public record TaskRecord(
        String taskId, String taskInput, TaskStatus status,
        String finalAnswer, String failureReason,
        LocalDateTime createdAt, LocalDateTime startedAt,
        LocalDateTime finishedAt, LocalDateTime updatedAt,
        String workerId, LocalDateTime heartbeatAt, LocalDateTime leaseUntil,
        String activeAttemptId, long fenceToken) {
    public TaskRecord(String taskId, String taskInput, TaskStatus status, String finalAnswer, String failureReason,
            LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, LocalDateTime updatedAt,
            String workerId, LocalDateTime heartbeatAt, LocalDateTime leaseUntil) {
        this(taskId, taskInput, status, finalAnswer, failureReason, createdAt, startedAt, finishedAt,
                updatedAt, workerId, heartbeatAt, leaseUntil, null, 0);
    }
}

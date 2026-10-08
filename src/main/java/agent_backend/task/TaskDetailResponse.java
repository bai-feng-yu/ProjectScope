package agent_backend.task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import agent_backend.persistence.model.TaskRecord;
import agent_backend.persistence.model.TaskStatus;

public record TaskDetailResponse(String taskId, String task, TaskStatus status,
        String finalAnswer, String failureReason, Instant createdAt, Instant startedAt,
        Instant finishedAt, Instant updatedAt, String workerId, Instant heartbeatAt, Instant leaseUntil) {
    public static TaskDetailResponse from(TaskRecord record) {
        return new TaskDetailResponse(record.taskId(), record.taskInput(), record.status(),
                record.finalAnswer(), record.failureReason(), utc(record.createdAt()), utc(record.startedAt()),
                utc(record.finishedAt()), utc(record.updatedAt()), record.workerId(), utc(record.heartbeatAt()), utc(record.leaseUntil()));
    }

    static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}

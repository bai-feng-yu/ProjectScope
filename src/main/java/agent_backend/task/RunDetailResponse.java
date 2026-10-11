package agent_backend.task;

import java.time.Instant;
import agent_backend.persistence.model.TaskRecord;
import agent_backend.persistence.model.TaskStatus;

public record RunDetailResponse(String runId, String input, TaskStatus status, String finalAnswer,
        String failureReason, Instant createdAt, Instant startedAt, Instant finishedAt,
        String activeAttemptId, long fenceToken) {
    static RunDetailResponse from(TaskRecord r) {
        return new RunDetailResponse(r.taskId(), r.taskInput(), r.status(), r.finalAnswer(), r.failureReason(),
                TaskDetailResponse.utc(r.createdAt()), TaskDetailResponse.utc(r.startedAt()),
                TaskDetailResponse.utc(r.finishedAt()), r.activeAttemptId(), r.fenceToken());
    }
}

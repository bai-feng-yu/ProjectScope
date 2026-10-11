package agent_backend.task;

import java.time.Instant;
import agent_backend.persistence.model.RunAttemptRecord;

public record RunAttemptResponse(String runId, String attemptId, long attemptNumber, long fenceToken,
        String workerId, String status, Instant startedAt, Instant heartbeatAt, Instant leaseUntil,
        Instant endedAt, String reason, boolean legacy) {
    static RunAttemptResponse from(RunAttemptRecord r) {
        return new RunAttemptResponse(r.runId(), r.attemptId(), r.attemptNumber(), r.fenceToken(),
                r.workerId(), r.status(), TaskDetailResponse.utc(r.startedAt()), TaskDetailResponse.utc(r.heartbeatAt()),
                TaskDetailResponse.utc(r.leaseUntil()), TaskDetailResponse.utc(r.endedAt()), r.reason(), r.legacy());
    }
}

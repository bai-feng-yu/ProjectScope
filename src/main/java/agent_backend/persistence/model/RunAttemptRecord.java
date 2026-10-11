package agent_backend.persistence.model;

import java.time.LocalDateTime;

/** Execution history; user ownership is inherited from the parent Run. Times are UTC. */
public record RunAttemptRecord(String runId, String attemptId, long attemptNumber, long fenceToken,
        String workerId, String status, LocalDateTime startedAt, LocalDateTime heartbeatAt,
        LocalDateTime leaseUntil, LocalDateTime endedAt, String reason, boolean legacy) {}

package agent_backend.persistence.model;

import java.time.LocalDateTime;

/** Arguments/result are serialized JSON. invocationId is distinct from the provider's toolCallId. */
public record ToolInvocationRecord(
        String invocationId, String stepId, String toolCallId, int callOrder,
        String toolName, String arguments, ToolCallStatus status,
        String result, String failureReason,
        LocalDateTime createdAt, LocalDateTime startedAt,
        LocalDateTime finishedAt, LocalDateTime updatedAt) {
}

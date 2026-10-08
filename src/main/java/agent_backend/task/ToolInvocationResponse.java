package agent_backend.task;

import java.time.Instant;
import agent_backend.persistence.model.ToolInvocationRecord;
import agent_backend.persistence.model.ToolCallStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record ToolInvocationResponse(String invocationId, String toolCallId, int callOrder,
        String toolName, JsonNode arguments, ToolCallStatus status, JsonNode result, String failureReason,
        Instant createdAt, Instant startedAt, Instant finishedAt, Instant updatedAt) {
    public static ToolInvocationResponse from(ToolInvocationRecord record, ObjectMapper mapper) {
        return new ToolInvocationResponse(record.invocationId(), record.toolCallId(), record.callOrder(),
                record.toolName(), mapper.readTree(record.arguments()), record.status(),
                record.result() == null ? null : mapper.readTree(record.result()), record.failureReason(),
                TaskDetailResponse.utc(record.createdAt()), TaskDetailResponse.utc(record.startedAt()),
                TaskDetailResponse.utc(record.finishedAt()), TaskDetailResponse.utc(record.updatedAt()));
    }
}

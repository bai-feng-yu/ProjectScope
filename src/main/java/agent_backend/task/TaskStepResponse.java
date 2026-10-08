package agent_backend.task;

import java.time.Instant;
import java.util.List;
import agent_backend.persistence.model.StepRecord;
import agent_backend.persistence.model.StepStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record TaskStepResponse(String stepId, int stepNumber, StepStatus status,
        JsonNode modelResponse, String failureReason, List<ToolInvocationResponse> calls,
        Instant createdAt, Instant finishedAt, Instant updatedAt) {
    public TaskStepResponse {
        calls = List.copyOf(calls);
    }

    public static TaskStepResponse from(StepRecord record, List<ToolInvocationResponse> calls, ObjectMapper mapper) {
        return new TaskStepResponse(record.stepId(), record.stepNumber(), record.status(),
                record.modelResponse() == null ? null : mapper.readTree(record.modelResponse()),
                record.failureReason(), calls, TaskDetailResponse.utc(record.createdAt()),
                TaskDetailResponse.utc(record.finishedAt()), TaskDetailResponse.utc(record.updatedAt()));
    }
}

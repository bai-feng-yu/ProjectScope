package agent_backend.task;

import jakarta.validation.constraints.NotBlank;

public record CreateTaskRequest(@NotBlank String task) {}

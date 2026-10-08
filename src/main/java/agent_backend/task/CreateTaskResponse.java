package agent_backend.task;

import agent_backend.persistence.model.TaskStatus;

/** Status at acceptance, not a promise that the worker has not started yet. */
public record CreateTaskResponse(String taskId, TaskStatus status) {}

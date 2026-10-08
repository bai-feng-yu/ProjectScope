package agent_backend.task;

public class TaskSubmissionRejectedException extends RuntimeException {
    private final String taskId;

    public TaskSubmissionRejectedException(String taskId, Throwable cause) {
        super("Task executor is unavailable; query the task for its persisted status", cause);
        this.taskId = taskId;
    }

    public String getTaskId() { return taskId; }
}

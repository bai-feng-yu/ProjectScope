package agent_backend.task;

import agent_backend.TaskControler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TaskControler.class)
public class TaskApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(TaskApiExceptionHandler.class);
    public record ApiError(String code, String message, String taskId) {}

    @ExceptionHandler(TaskSubmissionRejectedException.class)
    public ResponseEntity<ApiError> rejected(TaskSubmissionRejectedException exception) {
        log.warn("Task submission rejected for {}", exception.getTaskId(), exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError("TASK_SUBMISSION_REJECTED", exception.getMessage(), exception.getTaskId()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storageUnavailable(DataAccessException exception) {
        log.error("Task storage operation failed", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError("TASK_STORAGE_UNAVAILABLE", "Task storage is temporarily unavailable", null));
    }
}

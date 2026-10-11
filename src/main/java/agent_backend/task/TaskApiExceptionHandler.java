package agent_backend.task;

import agent_backend.TaskControler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 同时覆盖普通任务接口和 SSE 建连前的数据库错误；流建立后的错误由订阅服务关闭连接。
@RestControllerAdvice(assignableTypes = {TaskControler.class, TaskEventController.class,
        RunController.class, RunEventController.class})
public class TaskApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(TaskApiExceptionHandler.class);
    public record ApiError(String code, String message, String taskId) {}

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> storageUnavailable(DataAccessException exception) {
        log.error("Task storage operation failed", exception);
        // 即使客户端 Accept 是 text/event-stream，建连前的错误也明确使用 JSON 返回。
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(
                new ApiError("TASK_STORAGE_UNAVAILABLE", "Task storage is temporarily unavailable", null));
    }
}

package agent_backend.task;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/tasks")
public class TaskEventController {
    private final TaskSubscriptionService subscriptions;

    public TaskEventController(TaskSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable UUID taskId) {
        // UUID 由 Spring MVC 转换，非法格式会返回 400。
        // 返回 emitter 后进入异步响应，连接保持打开，HTTP 请求线程可返回线程池。
        // no-cache 要求缓存重验证；X-Accel-Buffering 请求 Nginx 不缓冲事件（需代理支持）。
        return ResponseEntity.ok().header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no").body(subscriptions.subscribe(taskId.toString()));
    }
}

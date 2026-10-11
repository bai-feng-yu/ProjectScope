package agent_backend.task;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/runs")
public class RunEventController {
    private final RunEventSubscriptionService subscriptions;
    public RunEventController(RunEventSubscriptionService subscriptions) { this.subscriptions = subscriptions; }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) String after) {
        // The browser's latest reconnect cursor takes precedence over the original query parameter.
        String raw = lastEventId != null ? lastEventId : after;
        Long cursor = null;
        if (raw != null) {
            try {
                if (!raw.matches("[0-9]{1,19}")) throw new NumberFormatException();
                cursor = Long.parseLong(raw);
            } catch (NumberFormatException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event cursor");
            }
        }
        var emitter = subscriptions.subscribe(runId.toString(), cursor);
        if (emitter == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no").body(emitter);
    }
}

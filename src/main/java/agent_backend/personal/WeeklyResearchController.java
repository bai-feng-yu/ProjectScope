package agent_backend.personal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import agent_backend.security.TaskIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/schedules")
public class WeeklyResearchController {
    public record Create(@NotBlank String conversationId, @NotBlank @Size(max=8000) String content,
            @NotNull Instant firstFireAt) {}
    public record Pause(boolean paused) {}
    private final WeeklyResearchStore schedules;
    public WeeklyResearchController(WeeklyResearchStore schedules) { this.schedules = schedules; }

    @GetMapping public List<WeeklyResearchStore.Schedule> list() { return schedules.list(TaskIdentity.current().userId()); }
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,String> create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create request) {
        return Map.of("scheduleId", schedules.create(TaskIdentity.current().userId(), request.conversationId(), request.content(), request.firstFireAt(), key));
    }
    @PutMapping("/{id}/pause")
    public void pause(@PathVariable String id, @RequestBody Pause request) { schedules.pause(TaskIdentity.current().userId(), id, request.paused()); }
}

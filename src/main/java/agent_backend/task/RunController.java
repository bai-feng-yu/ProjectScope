package agent_backend.task;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import agent_backend.persistence.model.TaskStatus;

@RestController
@RequestMapping("/runs")
public class RunController {
    private final TaskService service;
    public RunController(TaskService service) { this.service = service; }

    public record CreatedRun(String runId, TaskStatus status) {}

    @PostMapping
    public ResponseEntity<CreatedRun> create(@Valid @RequestBody CreateTaskRequest request) {
        var result = service.submit(request);
        return ResponseEntity.accepted().location(URI.create("/runs/" + result.taskId()))
                .body(new CreatedRun(result.taskId(), result.status()));
    }

    @GetMapping("/{runId}")
    public RunDetailResponse get(@PathVariable UUID runId) { return service.getRun(runId.toString()); }

    @GetMapping("/{runId}/attempts")
    public List<RunAttemptResponse> attempts(@PathVariable UUID runId) { return service.getAttempts(runId.toString()); }

    @GetMapping("/{runId}/steps")
    public List<TaskStepResponse> steps(@PathVariable UUID runId) { return service.getSteps(runId.toString()); }
}

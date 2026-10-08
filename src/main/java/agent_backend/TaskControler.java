package agent_backend;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import agent_backend.task.CreateTaskRequest;
import agent_backend.task.CreateTaskResponse;
import agent_backend.task.TaskDetailResponse;
import agent_backend.task.TaskService;
import agent_backend.task.TaskStepResponse;

@RestController 
@RequestMapping ("/tasks")
public class TaskControler {
    private final TaskService taskService;

    public TaskControler(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping 
    public ResponseEntity<CreateTaskResponse> tasks(@Valid @RequestBody CreateTaskRequest request) {
        var response = taskService.submit(request);
        return ResponseEntity.accepted().location(URI.create("/tasks/" + response.taskId())).body(response);
    }

    @GetMapping("/{taskId}")
    public TaskDetailResponse get(@PathVariable UUID taskId) {
        return taskService.get(taskId.toString());
    }

    @GetMapping("/{taskId}/steps")
    public List<TaskStepResponse> steps(@PathVariable UUID taskId) {
        return taskService.getSteps(taskId.toString());
    }
}

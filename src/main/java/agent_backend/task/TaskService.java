package agent_backend.task;

import java.util.List;
import java.util.UUID;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.persistence.model.TaskRecord;
import agent_backend.persistence.model.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskService {
    private final TaskStore tasks;
    private final StepStore steps;
    private final ToolInvocationStore invocations;
    private final ObjectMapper mapper;

    public TaskService(TaskStore tasks, StepStore steps, ToolInvocationStore invocations,
            ObjectMapper mapper) {
        this.tasks = tasks;
        this.steps = steps;
        this.invocations = invocations;
        this.mapper = mapper;
    }

    // The database is the durable queue; the scheduler sees this task only after commit.
    @Transactional
    public CreateTaskResponse submit(CreateTaskRequest request) {
        Assert.hasText(request.task(), "task must not be blank");
        String taskId = UUID.randomUUID().toString();
        tasks.create(taskId, request.task());
        return new CreateTaskResponse(taskId, TaskStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(String taskId) {
        return TaskDetailResponse.from(requireTask(taskId));
    }

    @Transactional(readOnly = true)
    public List<TaskStepResponse> getSteps(String taskId) {
        requireTask(taskId);
        return steps.findByTask(taskId).stream().map(step -> TaskStepResponse.from(step,
                invocations.findByStep(step.stepId()).stream()
                        .map(call -> ToolInvocationResponse.from(call, mapper)).toList(), mapper)).toList();
    }

    private TaskRecord requireTask(String taskId) {
        return tasks.find(taskId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }
}

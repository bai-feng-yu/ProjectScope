package agent_backend.task;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.persistence.model.TaskRecord;
import agent_backend.persistence.model.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskService {
    private final TaskStore tasks;
    private final StepStore steps;
    private final ToolInvocationStore invocations;
    private final TaskDispatcher dispatcher;
    private final ObjectMapper mapper;

    public TaskService(TaskStore tasks, StepStore steps, ToolInvocationStore invocations,
            TaskDispatcher dispatcher, ObjectMapper mapper) {
        this.tasks = tasks;
        this.steps = steps;
        this.invocations = invocations;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    // The single INSERT must auto-commit before another thread can load the task.
    @Transactional(propagation = Propagation.NEVER)
    public CreateTaskResponse submit(CreateTaskRequest request) {
        Assert.hasText(request.task(), "task must not be blank");
        String taskId = UUID.randomUUID().toString();
        tasks.create(taskId, request.task());
        try {
            dispatcher.dispatch(taskId);
        } catch (RejectedExecutionException exception) {
            try {
                tasks.fail(taskId, "Task submission rejected: executor capacity exhausted or shutting down");
            } catch (RuntimeException persistenceException) {
                exception.addSuppressed(persistenceException);
            }
            throw new TaskSubmissionRejectedException(taskId, exception);
        }
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

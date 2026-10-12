package agent_backend.task;

import java.util.List;
import java.util.UUID;
import agent_backend.security.TaskIdentity;
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
        tasks.createOwned(taskId, request.task(), TaskIdentity.current().userId());
        return new CreateTaskResponse(taskId, TaskStatus.PENDING);
    }

    @Transactional
    public CreateTaskResponse submit(CreateTaskRequest request, String key) {
        if (key == null) return submit(request); // Legacy clients remain compatible; the MVP client always sends a key.
        Assert.hasText(request.task(), "task must not be blank");
        String userId = TaskIdentity.current().userId();
        String id = tasks.createOwnedOnce(request.task(), userId, key);
        var run = tasks.findOwned(id, userId).orElseThrow();
        return new CreateTaskResponse(id, run.status());
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(String taskId) {
        return TaskDetailResponse.from(requireTask(taskId));
    }

    @Transactional(readOnly = true)
    public RunDetailResponse getRun(String runId) {
        return RunDetailResponse.from(requireTask(runId));
    }

    @Transactional
    public RunDetailResponse cancel(String runId) {
        tasks.cancelOwned(runId, TaskIdentity.current().userId());
        return RunDetailResponse.from(requireTask(runId));
    }

    @Transactional(readOnly = true)
    public List<RunAttemptResponse> getAttempts(String runId) {
        requireTask(runId);
        return tasks.findAttempts(runId, TaskIdentity.current().userId()).stream()
                .map(RunAttemptResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<TaskStepResponse> getSteps(String taskId) {
        requireTask(taskId);
        return readSteps(taskId);
    }

    // 由订阅服务通过 Spring 代理调用，查询在同一个短只读事务内完成。
    // 在 MySQL 默认 REPEATABLE READ 下，多次普通查询读取同一一致性视图。
    // 方法返回时事务已经结束，SSE 连接存续和网络发送不会一直占用该事务。
    @Transactional(readOnly = true)
    public TaskSnapshot getSnapshot(String taskId) {
        return getSnapshot(taskId, TaskIdentity.current());
    }

    @Transactional(readOnly = true)
    public TaskSnapshot getSnapshot(String taskId, TaskIdentity identity) {
        identity.requireValid();
        var task = TaskDetailResponse.from(tasks.findOwned(taskId, identity.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")));
        return new TaskSnapshot(task.taskId(), task.task(), task.status(), task.finalAnswer(),
                task.failureReason(), task.createdAt(), task.startedAt(), task.finishedAt(), readSteps(taskId));
    }

    private List<TaskStepResponse> readSteps(String taskId) {
        // 普通查询接口与 SSE 共用组装逻辑；此私有方法沿用外层公共方法开启的事务。
        return steps.findByTask(taskId).stream().map(step -> TaskStepResponse.from(step,
                invocations.findByStep(step.stepId()).stream()
                        .map(call -> ToolInvocationResponse.from(call, mapper)).toList(), mapper)).toList();
    }

    private TaskRecord requireTask(String taskId) {
        return tasks.findOwned(taskId, TaskIdentity.current().userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }
}

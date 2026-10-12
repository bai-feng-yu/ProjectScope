package agent_backend;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.TaskLease;
import agent_backend.task.TaskLeaseManager;
import agent_backend.task.LeaseSession;
import agent_backend.persistence.StepStore;
import agent_backend.persistence.ToolInvocationStore;
import agent_backend.tool.ToolBatchExecutor;
import agent_backend.tool.ToolExecutionContext;
import agent_backend.toolRegistry.ToolRegistry;

@Service
public class AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private final LlmClient llmClient;
    private final ToolBatchExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final TaskStore tasks;
    private final StepStore steps;
    private final ToolInvocationStore invocations;
    private final int maxSteps;
    private final TaskLeaseManager leases;

    public AgentRunner(LlmClient llmClient, ToolBatchExecutor toolExecutor, ToolRegistry toolRegistry,
            TaskStore tasks, StepStore steps, ToolInvocationStore invocations, TaskLeaseManager leases,
            @Value("${agent.max-steps:10}") int maxSteps) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.tasks = tasks;
        this.steps = steps;
        this.invocations = invocations;
        this.maxSteps = maxSteps;
        this.leases = leases;
    }

    // Deliberately not transactional: model requests and tool effects must not hold DB transactions.
    public AgentState run(String task) {
        AgentState state = new AgentState(task);
        try {
            Assert.hasText(task, "task must not be blank");
            tasks.create(state.getTaskId(), task);
        } catch (Exception exception) {
            log.error("Could not create task {}", state.getTaskId(), exception);
            state.fail(describe(exception));
            return state;
        }
        return execute(state);
    }

    /** Executes an already committed task. Its conditional start is the ownership boundary. */
    public AgentState runExisting(String taskId) {
        var task = tasks.find(taskId).orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        return execute(new AgentState(task.taskId(), task.taskInput()));
    }

    private AgentState execute(AgentState state) {
        LeaseSession session = null;
        TaskLease lease = null;
        String activeStepId = null;
        try {
            session = leases.acquire(state.getTaskId());
            lease = session.lease();
            // Owner comes from persisted task, never from HTTP thread-local state or model arguments.
            String userId = tasks.findOwner(state.getTaskId()).orElse(null);
            state.setTools(toolRegistry.getAllTools());
            for (int number = 1; number <= maxSteps; number++) {
                String stepId = UUID.randomUUID().toString();
                session.check();
                steps.create(lease, stepId, number);
                activeStepId = stepId;
                session.check();
                var response = llmClient.request(state);
                session.check();
                // Save the raw response even when parsing or truncation protection rejects it.
                steps.saveResponse(lease, stepId, response);
                LlmDecision decision = llmClient.parseDecision(response);
                if (decision.getDecisionType() == LlmDecision.DecsionType.TOOL_CALL) {
                    var results = toolExecutor.execute(new ToolExecutionContext(session, stepId, userId),
                            decision.getToolCalls());
                    var completedStep = new AgentStep(stepId, number, decision, results);
                    steps.complete(lease, stepId);
                    activeStepId = null;
                    state.addStep(completedStep);
                } else {
                    Assert.hasText(decision.getFinalAnswer(), "final answer must not be blank");
                    tasks.completeFinal(lease, stepId, decision.getFinalAnswer());
                    activeStepId = null;
                    state.complete(decision.getFinalAnswer());
                    return state;
                }
            }
            String reason = "Agent exceeded maximum steps: " + maxSteps;
            state.fail(persistFailure(lease, null, reason));
        } catch (Exception exception) {
            log.error("Agent execution failed for task {}", state.getTaskId(), exception);
            String reason = describe(exception);
            // A rejected start must never mark another executor's task FAILED.
            state.fail(lease != null ? persistFailure(lease, activeStepId, reason) : reason);
        } finally {
            if (session != null) session.close();
        }
        return state;
    }

    private String persistFailure(TaskLease lease, String stepId, String reason) {
        List<String> errors = new ArrayList<>();
        // Independent attempts: a failed cleanup must not prevent attempting the task's terminal write.
        if (stepId != null) {
            attempt(() -> invocations.abortUnfinished(lease, stepId, reason), errors);
            attempt(() -> steps.fail(lease, stepId, reason), errors);
        }
        attempt(() -> tasks.fail(lease, reason), errors);
        return errors.isEmpty() ? reason : reason + "; Failure persistence incomplete: " + String.join("; ", errors);
    }

    private void attempt(Runnable action, List<String> errors) {
        try {
            action.run();
        } catch (Exception exception) {
            log.error("Could not persist failure cleanup", exception);
            errors.add(describe(exception));
        }
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}

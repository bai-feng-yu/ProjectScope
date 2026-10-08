package agent_backend.task;

import agent_backend.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final AgentRunner runner;

    public TaskWorker(AgentRunner runner) { this.runner = runner; }

    public void execute(String taskId) {
        try {
            // Runner's conditional PENDING -> RUNNING update determines the sole execution owner.
            runner.runExisting(taskId);
        } catch (Exception exception) {
            // A read failure before claiming leaves the task PENDING for a later poll.
            log.error("Could not load or execute candidate task {}", taskId, exception);
        }
    }
}

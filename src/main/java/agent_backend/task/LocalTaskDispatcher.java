package agent_backend.task;

import agent_backend.AgentRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class LocalTaskDispatcher implements TaskDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalTaskDispatcher.class);
    private final ThreadPoolTaskExecutor executor;
    private final AgentRunner runner;

    public LocalTaskDispatcher(@Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor executor, AgentRunner runner) {
        this.executor = executor;
        this.runner = runner;
    }

    @Override
    public void dispatch(String taskId) {
        executor.execute(() -> {
            try {
                runner.runExisting(taskId);
            } catch (Exception exception) {
                // Loading a task can fail before Runner can own it; never overwrite a concurrent owner.
                log.error("Could not execute submitted task {}", taskId, exception);
            }
        });
    }
}

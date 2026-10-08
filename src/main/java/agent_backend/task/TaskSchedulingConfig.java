package agent_backend.task;

import agent_backend.persistence.TaskStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "agent.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class TaskSchedulingConfig {
    @Bean
    public PendingTaskScheduler pendingTaskScheduler(TaskStore tasks, TaskDispatcher dispatcher) {
        return new PendingTaskScheduler(tasks, dispatcher);
    }
}

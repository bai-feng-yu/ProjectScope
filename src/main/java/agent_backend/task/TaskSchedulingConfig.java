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
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("task-scheduler-");
        // Keep polling/recovery off the dedicated heartbeat executor.
        return scheduler;
    }

    @Bean
    public TaskRecoveryService taskRecoveryService(TaskStore tasks,
            @org.springframework.beans.factory.annotation.Value("${agent.lease.recovery-batch-size:100}") int batchSize) {
        return new TaskRecoveryService(tasks, batchSize);
    }

    @Bean
    public PendingTaskScheduler pendingTaskScheduler(TaskStore tasks, TaskDispatcher dispatcher) {
        return new PendingTaskScheduler(tasks, dispatcher);
    }
}

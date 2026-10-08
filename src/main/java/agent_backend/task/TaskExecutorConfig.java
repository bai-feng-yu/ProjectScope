package agent_backend.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;

@Configuration
public class TaskExecutorConfig {
    @Bean
    public ThreadPoolTaskExecutor agentTaskExecutor(
            @Value("${agent.executor.workers:2}") int workers,
            @Value("${agent.executor.queue-capacity:32}") int queueCapacity) {
        Assert.isTrue(workers > 0 && queueCapacity >= 0, "Invalid task executor capacity");
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-task-");
        // Default AbortPolicy: never run a rejected task in the HTTP request thread.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}

package agent_backend.task;

import agent_backend.persistence.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class PendingTaskScheduler {
    private static final Logger log = LoggerFactory.getLogger(PendingTaskScheduler.class);
    private final TaskStore tasks;
    private final TaskDispatcher dispatcher;

    public PendingTaskScheduler(TaskStore tasks, TaskDispatcher dispatcher) {
        this.tasks = tasks;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${agent.scheduler.poll-interval-ms:1000}")
    public synchronized void poll() {
        try {
            int capacity = dispatcher.availableCapacity();
            if (capacity == 0) return;
            for (String taskId : tasks.findPending(capacity)) {
                dispatcher.tryDispatch(taskId);
            }
        } catch (Exception exception) {
            // No task has been claimed by this scheduler. The next poll can safely retry.
            log.error("Pending task polling failed; will retry on the next poll", exception);
        }
    }
}

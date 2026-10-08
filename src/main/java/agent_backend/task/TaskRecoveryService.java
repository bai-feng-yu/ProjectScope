package agent_backend.task;

import agent_backend.persistence.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class TaskRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);
    private final TaskStore tasks;
    private final int batchSize;

    public TaskRecoveryService(TaskStore tasks, int batchSize) {
        org.springframework.util.Assert.isTrue(batchSize > 0, "Recovery batch size must be positive");
        this.tasks = tasks;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agent.lease.recovery-interval-ms:10000}")
    public void recover() {
        try {
            for (String id : tasks.findExpired(batchSize)) {
                try {
                    tasks.recoverExpired(id);
                } catch (Exception exception) {
                    log.error("Could not recover expired task {}", id, exception);
                }
            }
        } catch (Exception exception) {
            log.error("Could not scan expired tasks; will retry", exception);
        }
    }
}

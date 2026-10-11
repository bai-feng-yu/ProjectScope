package agent_backend.persistence;

import java.util.Optional;
import java.util.List;
import agent_backend.persistence.model.TaskRecord;

public interface TaskStore {
    void create(String taskId, String input);
    void createOwned(String taskId, String input, String userId);
    Optional<TaskRecord> findOwned(String taskId, String userId);
    Optional<String> findOwner(String taskId);
    TaskLease start(TaskLease lease, long durationMs);
    List<agent_backend.persistence.model.RunAttemptRecord> findAttempts(String taskId, String userId);
    boolean renew(TaskLease lease, long durationMs);
    void complete(TaskLease lease, String answer);
    void fail(TaskLease lease, String reason);
    void failPending(String taskId, String reason);
    Optional<TaskRecord> find(String taskId);
    List<String> findPending(int limit);
    List<String> findExpired(int limit);
    boolean recoverExpired(String taskId);
}

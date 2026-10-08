package agent_backend.persistence;

import java.util.Optional;
import java.util.List;
import agent_backend.persistence.model.TaskRecord;

public interface TaskStore {
    void create(String taskId, String input);
    void start(TaskLease lease, long durationMs);
    boolean renew(TaskLease lease, long durationMs);
    void complete(TaskLease lease, String answer);
    void fail(TaskLease lease, String reason);
    void failPending(String taskId, String reason);
    Optional<TaskRecord> find(String taskId);
    List<String> findPending(int limit);
    List<String> findExpired(int limit);
    boolean recoverExpired(String taskId);
}

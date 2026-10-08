package agent_backend.persistence;

import java.util.Optional;
import java.util.List;
import agent_backend.persistence.model.TaskRecord;

public interface TaskStore {
    void create(String taskId, String input);
    void start(String taskId);
    void complete(String taskId, String answer);
    void fail(String taskId, String reason);
    Optional<TaskRecord> find(String taskId);
    List<String> findPending(int limit);
}

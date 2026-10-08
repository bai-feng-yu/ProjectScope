package agent_backend.persistence;

import java.util.List;
import agent_backend.persistence.model.StepRecord;

public interface StepStore {
    void create(TaskLease lease, String stepId, int number);
    void saveResponse(TaskLease lease, String stepId, Object response);
    void complete(TaskLease lease, String stepId);
    void fail(TaskLease lease, String stepId, String reason);
    List<StepRecord> findByTask(String taskId);
}

package agent_backend.persistence;

import java.util.List;
import agent_backend.persistence.model.StepRecord;

public interface StepStore {
    void create(String stepId, String taskId, int number);
    void saveResponse(String stepId, Object response);
    void complete(String stepId);
    void fail(String stepId, String reason);
    List<StepRecord> findByTask(String taskId);
}

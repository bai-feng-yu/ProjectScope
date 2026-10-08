package agent_backend.task;

/** Schedules a committed task without running the agent on the caller's thread. */
public interface TaskDispatcher {
    void dispatch(String taskId);
}

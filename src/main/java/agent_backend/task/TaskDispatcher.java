package agent_backend.task;

/** Reserves local execution capacity before handing a database candidate to a worker. */
public interface TaskDispatcher {
    int availableCapacity();
    boolean tryDispatch(String taskId);
}

package agent_backend;

public class ChatRequest {
    private String task;

    public String getTask() {
        return task;
    }

    public ChatRequest(String task){
        this.task = task;
    }
}

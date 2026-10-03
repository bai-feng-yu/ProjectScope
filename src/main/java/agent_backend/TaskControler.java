package agent_backend;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController 
@RequestMapping ("/tasks")
public class TaskControler {
    private final AgentRunner agentRunner;

    public TaskControler(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
    }

    @PostMapping 
    public String tasks(@RequestBody ChatRequest request){

        String task = request.getTask();
        AgentState agentState = agentRunner.run(task);
        return agentState.getFinalAnswer();
    }
    
}

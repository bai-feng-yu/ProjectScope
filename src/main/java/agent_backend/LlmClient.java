package agent_backend;

import org.springframework.stereotype.Service;

@Service 
public class LlmClient {
    public LlmDecision decide(AgentState agentState){
        // Implementation of the decide method

        // TODO : Implement the logic to interact with the LLM and make a decision based on the agentState
        return new LlmDecision(LlmDecision.DecsionType.FINAL_ANSWER, null, "Default answer");
    }


}

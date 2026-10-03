package agent_backend;


import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class AgentRunner {

    private final LlmClient llmClient;
    private final ToolCall toolCall;
        
    public AgentRunner(LlmClient llmClient, ToolCall toolCall){
        this.llmClient = llmClient;
        this.toolCall = toolCall;
    }


    public AgentState run (String task){
        task += " You can find the list of available tools and their descriptions by calling the /tools endpoint."; // TODO: 让模型知道如何获取工具以及相关模板
        AgentState agentState = new AgentState(task);

        while(true){
            LlmDecision llmDecision = this.llmClient.decide(agentState);        

            if(llmDecision.getDecisionType()==LlmDecision.DecsionType.TOOL_CALL){
                Map<String, Object> toolCallArguments = llmDecision.getToolCallArguments();
                ToolResult toolResult = toolCall.callTool(toolCallArguments);
                agentState.addStep(new AgentStep(llmDecision, toolResult));//  Update the agentState with the result of the tool call
            }else if (llmDecision.getDecisionType()==LlmDecision.DecsionType.FINAL_ANSWER){
                String finalAnswer = llmDecision.getFinalAnswer();
                System.out.println("Final Answer: " + finalAnswer);
                break;
            }
        }

        return agentState;
    }

}

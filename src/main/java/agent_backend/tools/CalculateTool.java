
package agent_backend.tools;

import agent_backend.AgentTool;
import agent_backend.ToolResult;
import java.util.Map;
import org.springframework.stereotype.Component;


@Component
public class CalculateTool implements AgentTool{
    public String calculate(String expression) {
        // Implement the calculation logic here
        // For simplicity, let's just return the expression for now
        return "Result of calculation for: " + expression;
    }
    
    @Override
    public String name() {
        return "CalculateTool";
    }

    @Override
    public String description() {
        return "A tool for calculating expressions";
    }
    
    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String operation = (String) arguments.get("operation");

        if(operation == null){
            return new ToolResult(name(), "Operation is required");
        }else if(operation=="multiply"){
            int left=(Integer) arguments.get("left");
            int right=(Integer) arguments.get("right");
            Integer result=left*right;
            return new ToolResult(name(), result.toString());
        }

        return new ToolResult(name(), "Invalid operation");
    }

}
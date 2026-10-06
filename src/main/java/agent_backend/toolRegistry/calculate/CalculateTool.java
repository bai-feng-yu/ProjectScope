
package agent_backend.toolRegistry.calculate;

import org.springframework.stereotype.Component;

import agent_backend.tool.AgentTool;


@Component
public class CalculateTool implements AgentTool<CalculateInput, CalculateOutput>    {

    @Override
    public Class<CalculateInput> inputType() {
        return CalculateInput.class;
    }
    
    @Override 
    public Class<CalculateOutput> outputType() {
        return CalculateOutput.class;
    }

    @Override 
    public CalculateInput inputExample() {
        return new CalculateInput("add", 1, 2);
    }

    @Override 
    public CalculateOutput outputExample() {
        return new CalculateOutput(3);
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
    public CalculateOutput execute(CalculateInput input) {
        String operation = input.operation();
        Integer left = input.left();
        Integer right = input.right();

        if(operation == null){
            return new CalculateOutput(null);
        }else if("multiply".equals(operation)){

            Integer result=left*right;
            return new CalculateOutput(result);
        }

        return new CalculateOutput(null);
    }

}
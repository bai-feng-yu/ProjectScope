package agent_backend.toolRegistry.calculate;


public record CalculateInput(
    String operation,
    Integer left,
    Integer right
) {} 

package agent_backend.tool;

public interface AgentTool<I, O> {
    public String name();
    public String description();

    Class<I> inputType();
    Class<O> outputType();

    I inputExample();

    O outputExample();

    public O execute(I input);

}


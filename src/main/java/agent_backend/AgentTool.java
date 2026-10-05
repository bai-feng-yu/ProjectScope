package agent_backend;

import tools.jackson.databind.json.JsonMapper;

public interface AgentTool<I, O> {
    public String name();
    public String description();

    Class<I> inputType();
    Class<O> outputType();

    I inputExample();

    O outputExample();

    public O execute(I input);

    // 一个用于让调度器能够安全调用 exec 方法的默认实现
    default ToolResult<O> executeRaw( 
        Object rawInput,
        JsonMapper mapper
    ) {
        try {
                I input = mapper.convertValue(rawInput, inputType());
                return new ToolSuccess<>(name(), execute(input));
            } catch (IllegalArgumentException e) {
                // LLM 调度器在调用 execute 方法时，可能会传入不符合要求的参数类型，这里捕获异常并返回一个 ToolFailure 对象，表示调用失败
                return new ToolFailure<>( name(), "INVALID_ARGUMENT", e.getMessage() );
            }
    }
}


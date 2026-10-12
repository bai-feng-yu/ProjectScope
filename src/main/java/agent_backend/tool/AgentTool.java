package agent_backend.tool;

public interface AgentTool<I, O> {
    public String name();
    public String description();

    Class<I> inputType();
    Class<O> outputType();

    I inputExample();

    O outputExample();

    public O execute(I input);

    default RecoveryPolicy recoveryPolicy() { return RecoveryPolicy.MANUAL_ONLY; }

    /** Version the external operation/key contract when changing adapter semantics. */
    default String recoveryVersion() { return "1"; }

    default O execute(I input, ToolExecutionContext context) { return execute(input); }

    /** Read-only external lookup. Must use a bounded transport timeout and never re-execute the operation. */
    default Reconciliation reconcile(I input, ToolRecoveryContext context) {
        return new Reconciliation(Reconciliation.Outcome.INDETERMINATE, null, "Adapter has no lookup capability");
    }

}


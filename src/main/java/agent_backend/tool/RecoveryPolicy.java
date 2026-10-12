package agent_backend.tool;

/** Adapter-owned capability, never supplied by the model or API caller. */
public enum RecoveryPolicy { READ_ONLY, IDEMPOTENT, RECONCILABLE, MANUAL_ONLY }

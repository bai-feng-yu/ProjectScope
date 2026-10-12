ALTER TABLE agent_tool_call
    ADD COLUMN idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN recovery_policy VARCHAR(24) NOT NULL DEFAULT 'MANUAL_ONLY',
    ADD COLUMN recovery_version VARCHAR(64) NOT NULL DEFAULT 'legacy',
    ADD COLUMN recovery_resolution VARCHAR(24) NULL,
    ADD CONSTRAINT chk_recovery_policy CHECK (recovery_policy IN ('READ_ONLY','IDEMPOTENT','RECONCILABLE','MANUAL_ONLY'));
UPDATE agent_tool_call SET idempotency_key=invocation_id;
ALTER TABLE agent_tool_call ADD UNIQUE KEY uk_tool_idempotency (idempotency_key);

CREATE TABLE tool_execution_attempt (
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) NOT NULL,
    result JSON NULL,
    failure_reason TEXT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    ended_at DATETIME(6) NULL,
    legacy BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_tool_execution (invocation_id, run_attempt_id),
    FOREIGN KEY (invocation_id) REFERENCES agent_tool_call(invocation_id),
    FOREIGN KEY (run_id, run_attempt_id) REFERENCES run_attempt(run_id, attempt_id),
    CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN'))
) ENGINE=InnoDB;

-- One observed historical execution, not invented retry history.
INSERT INTO tool_execution_attempt (execution_id,invocation_id,run_id,run_attempt_id,status,
    result,failure_reason,started_at,ended_at,legacy)
SELECT UUID(),c.invocation_id,s.task_id,s.created_attempt_id,c.status,c.result,c.failure_reason,
    COALESCE(c.started_at,c.created_at),c.finished_at,TRUE
FROM agent_tool_call c JOIN agent_step s ON s.step_id=c.step_id WHERE c.status <> 'PENDING';

CREATE TABLE tool_recovery_attempt (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    outcome VARCHAR(24) NULL,
    evidence TEXT NULL,
    output JSON NULL,
    started_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    lease_until DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    KEY idx_recovery_invocation (invocation_id, status, started_at),
    FOREIGN KEY (invocation_id) REFERENCES agent_tool_call(invocation_id),
    CHECK (action IN ('RECONCILE','CONFIRM')),
    CHECK (status IN ('RUNNING','DONE','EXPIRED')),
    CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED','NOT_EXECUTED','IN_PROGRESS','INDETERMINATE'))
) ENGINE=InnoDB;

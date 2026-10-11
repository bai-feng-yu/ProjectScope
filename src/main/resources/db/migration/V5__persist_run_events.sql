ALTER TABLE agent_task ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_run_event_seq CHECK (last_event_seq >= 0);

CREATE TABLE run_event (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (run_id, seq),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES agent_task(task_id),
    CONSTRAINT chk_event_seq CHECK (seq > 0)
) ENGINE=InnoDB;

-- Historical executions have no event history. Import only their observed state.
INSERT INTO run_event (run_id, seq, event_type, payload)
SELECT task_id, 1, 'run.imported', JSON_OBJECT('status', status) FROM agent_task;
UPDATE agent_task SET last_event_seq = 1;

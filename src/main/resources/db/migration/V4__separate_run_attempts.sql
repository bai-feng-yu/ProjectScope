-- Offline upgrade: stop all old workers first. agent_task is the compatibility name for a Run.
ALTER TABLE agent_task
    ADD COLUMN active_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN fence_token BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_run_fence CHECK (fence_token >= 0);

CREATE TABLE run_attempt (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_number BIGINT NOT NULL,
    fence_token BIGINT NOT NULL,
    worker_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    started_at DATETIME(6) NULL,
    heartbeat_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    reason TEXT NULL,
    legacy BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (run_id, attempt_id),
    UNIQUE KEY uk_attempt_number (run_id, attempt_number),
    UNIQUE KEY uk_attempt_fence (run_id, fence_token),
    KEY idx_attempt_expiry (status, lease_until, run_id),
    CONSTRAINT fk_attempt_run FOREIGN KEY (run_id) REFERENCES agent_task(task_id),
    CONSTRAINT chk_attempt_number CHECK (attempt_number > 0 AND fence_token > 0),
    CONSTRAINT chk_attempt_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preserve only an observed execution; do not invent historical retries or missing timestamps.
INSERT INTO run_attempt (run_id, attempt_id, attempt_number, fence_token, worker_id,
    status, started_at, heartbeat_at, lease_until, ended_at, reason, legacy)
SELECT task_id, UUID(), 1, 1, worker_id,
    CASE status WHEN 'COMPLETED' THEN 'SUCCEEDED' WHEN 'RUNNING' THEN 'RUNNING' ELSE 'FAILED' END,
    started_at, heartbeat_at, lease_until, finished_at, failure_reason, TRUE
FROM agent_task WHERE status = 'RUNNING' OR started_at IS NOT NULL OR worker_id IS NOT NULL;

UPDATE agent_task r JOIN run_attempt a ON a.run_id = r.task_id
SET r.fence_token = 1, r.active_attempt_id = CASE WHEN r.status = 'RUNNING' THEN a.attempt_id ELSE NULL END;

ALTER TABLE agent_task
    ADD CONSTRAINT fk_run_active_attempt FOREIGN KEY (task_id, active_attempt_id)
        REFERENCES run_attempt(run_id, attempt_id),
    DROP INDEX idx_task_status_lease,
    DROP COLUMN worker_id,
    DROP COLUMN heartbeat_at,
    DROP COLUMN lease_until;

ALTER TABLE agent_step
    ADD COLUMN created_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_step_attempt FOREIGN KEY (task_id, created_attempt_id)
        REFERENCES run_attempt(run_id, attempt_id);
UPDATE agent_step s JOIN run_attempt a ON a.run_id = s.task_id AND a.legacy = TRUE
SET s.created_attempt_id = a.attempt_id;

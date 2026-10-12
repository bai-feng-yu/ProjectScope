ALTER TABLE agent_task ADD COLUMN deadline_at DATETIME(6) NULL,
    DROP CHECK chk_task_status,
    ADD CONSTRAINT chk_task_status CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED'));
ALTER TABLE run_attempt DROP CHECK chk_attempt_status,
    ADD CONSTRAINT chk_attempt_status CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED','CANCELLED'));
CREATE INDEX idx_run_owner_status ON agent_task(user_id,status);

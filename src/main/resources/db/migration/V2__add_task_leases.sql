ALTER TABLE agent_task
    ADD COLUMN worker_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN heartbeat_at DATETIME(6) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD KEY idx_task_status_lease (status, lease_until, task_id);

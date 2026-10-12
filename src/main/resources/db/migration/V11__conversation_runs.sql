ALTER TABLE conversation_message ADD COLUMN run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_message_run(run_id),
    ADD CONSTRAINT fk_message_run FOREIGN KEY(run_id) REFERENCES agent_task(task_id);

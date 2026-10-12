CREATE TABLE scheduled_research (
    schedule_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(8000) NOT NULL,
    next_fire_at DATETIME(6) NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    last_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_outcome VARCHAR(32) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    INDEX idx_schedule_due(paused,next_fire_at),
    INDEX idx_schedule_owner(user_id,created_at),
    FOREIGN KEY(user_id,conversation_id) REFERENCES conversation(user_id,conversation_id),
    FOREIGN KEY(last_run_id) REFERENCES agent_task(task_id)
) ENGINE=InnoDB;

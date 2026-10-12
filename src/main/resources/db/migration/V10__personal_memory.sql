CREATE TABLE memory_source_processed (
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(user_id,message_id),
    FOREIGN KEY(user_id,message_id) REFERENCES conversation_message(user_id,message_id)
) ENGINE=InnoDB;
CREATE TABLE personal_memory (
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
    version BIGINT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    INDEX idx_memory_user(user_id,status,updated_at),
    FOREIGN KEY(user_id,source_message_id) REFERENCES conversation_message(user_id,message_id),
    CHECK (status IN ('CANDIDATE','ACTIVE','SUPERSEDED','DELETED')),
    CHECK (kind IN ('FACT','PREFERENCE','INFERENCE'))
) ENGINE=InnoDB;

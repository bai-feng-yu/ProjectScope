CREATE TABLE conversation (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE KEY uk_conversation_owner(user_id,conversation_id)
) ENGINE=InnoDB;
CREATE TABLE conversation_message (
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reply_to CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE KEY uk_message_request(conversation_id,request_key),
    UNIQUE KEY uk_message_reply(reply_to),
    UNIQUE KEY uk_message_owner(user_id,message_id),
    FOREIGN KEY (user_id,conversation_id) REFERENCES conversation(user_id,conversation_id),
    FOREIGN KEY (reply_to) REFERENCES conversation_message(message_id),
    CHECK (role IN ('USER','ASSISTANT')),
    CHECK (status IN ('PENDING','COMPLETED','FAILED'))
) ENGINE=InnoDB;

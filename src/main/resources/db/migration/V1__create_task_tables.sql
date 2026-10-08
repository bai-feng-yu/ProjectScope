-- MySQL 8.4+. All DATETIME values are UTC. IDs are application-generated UUID strings.
CREATE TABLE agent_task (
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_input LONGTEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    final_answer LONGTEXT NULL,
    failure_reason TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (task_id),
    KEY idx_task_status_created (status, created_at, task_id),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_step (
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_number INT NOT NULL COMMENT '1-based model turn number, including final-answer turns',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RUNNING',
    model_response JSON NULL COMMENT 'Complete response, including finish_reason; null before reception',
    failure_reason TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (step_id),
    CONSTRAINT uk_step_task_number UNIQUE (task_id, step_number),
    CONSTRAINT fk_step_task FOREIGN KEY (task_id) REFERENCES agent_task (task_id),
    CONSTRAINT chk_step_number CHECK (step_number > 0),
    CONSTRAINT chk_step_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_tool_call (
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    step_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tool_call_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    call_order INT NOT NULL COMMENT '1-based order within the model turn',
    tool_name VARCHAR(255) NOT NULL,
    arguments JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    result JSON NULL COMMENT 'Serialized ToolSuccess or ToolFailure',
    failure_reason TEXT NULL COMMENT 'Includes interruption details when outcome is UNKNOWN',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invocation_id),
    CONSTRAINT uk_call_step_order UNIQUE (step_id, call_order),
    CONSTRAINT uk_call_step_protocol_id UNIQUE (step_id, tool_call_id),
    CONSTRAINT fk_call_step FOREIGN KEY (step_id) REFERENCES agent_step (step_id),
    CONSTRAINT chk_call_order CHECK (call_order > 0),
    CONSTRAINT chk_call_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

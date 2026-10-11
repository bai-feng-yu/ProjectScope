-- NULL means legacy/internal task, never visible through user-facing APIs.
-- Ownership is immutable during execution; do not guess an owner during migration.
ALTER TABLE agent_task
    ADD COLUMN user_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_task_user_created (user_id, created_at, task_id);

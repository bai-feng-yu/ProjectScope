ALTER TABLE scheduled_research ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    ADD CONSTRAINT chk_schedule_timezone CHECK(time_zone='Asia/Shanghai');

CREATE TABLE platform_work_cursor (
    platform_worker_id BIGINT NOT NULL PRIMARY KEY,
    cursor_value TEXT,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Nullable additions preserve events already stored before polling was introduced.
ALTER TABLE platform_work_event
    ADD COLUMN source_payload JSON;

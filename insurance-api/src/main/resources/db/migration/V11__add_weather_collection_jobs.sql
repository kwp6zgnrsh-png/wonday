-- 원본과 실행 이력을 분리한다. 이전 수집 원본은 변경하거나 이력으로 추측해 채우지 않는다.
CREATE TABLE weather_collection_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    station_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_date DATE NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    lease_expires_at DATETIME(6),
    last_error_code VARCHAR(64),
    last_collection_id BIGINT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_weather_job_target (station_code, observation_date),
    KEY ix_weather_job_state (state, id),
    FOREIGN KEY (last_collection_id) REFERENCES weather_collection (id),
    CONSTRAINT ck_weather_job_state CHECK (state IN ('RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_weather_job_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_weather_job_lease CHECK ((state = 'RUNNING') = (lease_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE weather_collection_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    attempt_number INT NOT NULL,
    requested_by_member_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6),
    error_code VARCHAR(64),
    collection_id BIGINT,
    UNIQUE KEY uq_weather_attempt_number (job_id, attempt_number),
    UNIQUE KEY uq_weather_attempt_request (requested_by_member_id, request_key),
    KEY ix_weather_attempt_history (job_id, id),
    FOREIGN KEY (job_id) REFERENCES weather_collection_job (id),
    FOREIGN KEY (requested_by_member_id) REFERENCES member (id),
    FOREIGN KEY (collection_id) REFERENCES weather_collection (id),
    CONSTRAINT ck_weather_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_weather_attempt_trigger CHECK (trigger_type IN ('COLLECT','RETRY')),
    CONSTRAINT ck_weather_attempt_result CHECK (
        (state = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL AND collection_id IS NULL)
        OR (state = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL AND collection_id IS NOT NULL)
        OR (state = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL AND collection_id IS NULL)
    ),
    CONSTRAINT ck_weather_attempt_time CHECK (finished_at IS NULL OR finished_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

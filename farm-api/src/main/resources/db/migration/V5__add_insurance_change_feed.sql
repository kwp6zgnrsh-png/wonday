-- 기존 약정의 날짜·시간대·확정 시각 기준은 추측하지 않는다. 기존 행은 원본 확인 후 별도로 보완한다.
ALTER TABLE work_assignment
    ADD COLUMN agreed_work_date DATE NULL,
    ADD COLUMN agreed_time_zone VARCHAR(64) NULL,
    ADD COLUMN confirmed_at_utc DATETIME(6) NULL;

-- 근로자별 발행 번호를 잠가 버전 저장과 커밋 순서가 뒤집혀도 조회에서 빠지지 않게 한다.
CREATE TABLE insurance_change_stream (
    worker_id BIGINT NOT NULL PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    FOREIGN KEY (worker_id) REFERENCES member (id),
    CHECK (last_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 개인별 근무 변경의 현재 상태. 시각은 UTC로 저장하고 API에서 약정 시간대의 오프셋을 붙인다.
CREATE TABLE work_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    weather_reason_code VARCHAR(40) NULL,
    reason_detail TEXT NULL,
    effective_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    source_version BIGINT NOT NULL,
    UNIQUE KEY uq_change_assignment (assignment_id),
    FOREIGN KEY (assignment_id) REFERENCES work_assignment (id),
    FOREIGN KEY (actor_id) REFERENCES member (id),
    CHECK (source_version > 0),
    CHECK (change_type IN ('CANCELLED', 'EARLY_ENDED')),
    CHECK (reason_code IN ('WEATHER', 'FARM_CIRCUMSTANCES', 'OTHER')),
    CHECK (weather_reason_code IS NULL OR
           (reason_code = 'WEATHER' AND weather_reason_code IN ('RAIN', 'WIND', 'HEAT', 'OTHER')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 폴링은 이 불변 버전들을 읽는다. 같은 change_id + source_version의 본문을 덮어쓰지 않는다.
CREATE TABLE work_change_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    worker_id BIGINT NOT NULL,
    change_id BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    assignment_id BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    weather_reason_code VARCHAR(40) NULL,
    reason_detail TEXT NULL,
    event_time_zone VARCHAR(64) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_change_version (change_id, source_version),
    UNIQUE KEY uq_worker_sequence (worker_id, sequence_no),
    FOREIGN KEY (worker_id) REFERENCES member (id),
    FOREIGN KEY (change_id) REFERENCES work_change (id),
    FOREIGN KEY (assignment_id) REFERENCES work_assignment (id),
    CHECK (source_version > 0 AND sequence_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 동일한 조회 위치는 같은 불투명 커서를 재사용한다. 빈 폴링마다 새 행을 만들지 않는다.
CREATE TABLE insurance_change_cursor (
    token VARCHAR(36) NOT NULL PRIMARY KEY,
    worker_id BIGINT NOT NULL,
    after_sequence BIGINT NOT NULL,
    UNIQUE KEY uq_cursor_worker_position (worker_id, after_sequence),
    FOREIGN KEY (worker_id) REFERENCES member (id),
    CHECK (after_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

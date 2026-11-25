-- 기존 마이그레이션과 확정 근무 약정은 수정하지 않는다.
-- 기존 일정의 과거 조건을 추측해 채우지 않고, 첫 실제 수정 때 현재 조건을 BASELINE으로 보관한다.
CREATE TABLE work_schedule_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    work_schedule_id BIGINT NOT NULL,
    terms_version INT NOT NULL,
    revision_type VARCHAR(32) NOT NULL,
    actor_id BIGINT NULL,
    recorded_at_utc DATETIME(6) NOT NULL,
    work_date DATE NOT NULL,
    planned_start_at DATETIME(6) NOT NULL,
    planned_end_at DATETIME(6) NOT NULL,
    break_start_at DATETIME(6) NULL,
    break_end_at DATETIME(6) NULL,
    time_zone VARCHAR(64) NULL,
    work_location VARCHAR(500) NOT NULL,
    crop_code VARCHAR(40) NOT NULL,
    work_type_code VARCHAR(40) NOT NULL,
    headcount INT NOT NULL,
    daily_wage BIGINT NOT NULL,
    UNIQUE KEY uq_schedule_revision_version (work_schedule_id, terms_version),
    FOREIGN KEY (work_schedule_id) REFERENCES work_schedule (id),
    FOREIGN KEY (actor_id) REFERENCES member (id),
    CHECK (terms_version > 0 AND headcount > 0 AND daily_wage > 0),
    CHECK (revision_type IN ('BASELINE', 'UPDATED')),
    CHECK ((revision_type = 'BASELINE' AND actor_id IS NULL) OR
           (revision_type = 'UPDATED' AND actor_id IS NOT NULL)),
    CHECK (planned_end_at > planned_start_at),
    CHECK ((break_start_at IS NULL AND break_end_at IS NULL) OR
           (break_start_at IS NOT NULL AND break_end_at IS NOT NULL AND
            break_start_at >= planned_start_at AND break_end_at <= planned_end_at AND
            break_end_at > break_start_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

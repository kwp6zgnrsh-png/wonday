-- 기존 지원 ID·상태·시각과 확정 근무 FK는 보존한다. 기존 행은 1회차·현재 회차다.
-- 접수 당시 버전과 UTC 시각을 알 수 없는 기존 행은 NULL로 두고 추측하지 않는다.
ALTER TABLE application
    ADD COLUMN application_round INT NOT NULL DEFAULT 1,
    ADD COLUMN applied_terms_version INT NULL,
    ADD COLUMN applied_at_utc DATETIME(6) NULL,
    ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN current_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN is_current = 1 THEN 1 ELSE NULL END
    ) STORED,
    DROP INDEX uq_application_schedule_worker,
    ADD UNIQUE KEY uq_application_round (work_schedule_id, worker_id, application_round),
    ADD UNIQUE KEY uq_application_current (work_schedule_id, worker_id, current_marker),
    ADD KEY ix_application_worker_current (worker_id, is_current, work_schedule_id),
    ADD CONSTRAINT ck_application_round CHECK (application_round > 0),
    ADD CONSTRAINT ck_application_terms_version CHECK (applied_terms_version IS NULL OR applied_terms_version > 0),
    ADD CONSTRAINT ck_application_is_current CHECK (is_current IN (0, 1));

-- 동일 근로자의 요청은 공고와 무관하게 직렬화한다. 성공 기록이 없는 새 키도 같은 잠금을 쓴다.
CREATE TABLE application_request_lock (
    worker_id BIGINT NOT NULL PRIMARY KEY,
    FOREIGN KEY (worker_id) REFERENCES member (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 성공 접수의 불변 응답만 지원 행과 같은 트랜잭션으로 보관한다. 자동 만료는 없다.
CREATE TABLE application_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    worker_id BIGINT NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_posting_id BIGINT NOT NULL,
    request_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_payload TEXT NOT NULL,
    created_at_utc DATETIME(6) NOT NULL,
    UNIQUE KEY uq_application_receipt_worker_key (worker_id, request_key),
    FOREIGN KEY (worker_id) REFERENCES member (id),
    FOREIGN KEY (job_posting_id) REFERENCES job_posting (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
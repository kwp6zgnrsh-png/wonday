-- 연결이 철회되어도 소유자별 과거 판정은 보존한다. 복합 FK로 다른 회원의 연결을 근거로 저장할 수 없게 한다.
ALTER TABLE platform_link ADD UNIQUE KEY uq_link_reward_owner (id, member_id);

CREATE TABLE salary_reward_check (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    platform_link_id BIGINT NOT NULL,
    platform_worker_id BIGINT NOT NULL,
    consent_version VARCHAR(64) NOT NULL,
    request_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result VARCHAR(16) NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    registered BOOLEAN NULL,
    bank_code VARCHAR(3) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_error_code VARCHAR(64) NULL,
    rule_version VARCHAR(64) NOT NULL,
    checked_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_salary_reward_request (member_id, request_key),
    KEY ix_salary_reward_history (member_id, id),
    FOREIGN KEY (member_id) REFERENCES member (id),
    FOREIGN KEY (platform_link_id, member_id) REFERENCES platform_link (id, member_id),
    CONSTRAINT ck_reward_worker CHECK (platform_worker_id > 0),
    CONSTRAINT ck_reward_result CHECK (
        (result = 'ELIGIBLE' AND reason_code = 'WOORI_SALARY_ACCOUNT' AND registered IS NOT NULL
            AND registered = 1 AND bank_code IS NOT NULL AND bank_code = '020' AND source_error_code IS NULL)
        OR (result = 'INELIGIBLE' AND reason_code = 'OTHER_BANK' AND registered IS NOT NULL AND registered = 1
            AND bank_code IS NOT NULL AND bank_code REGEXP '^[0-9]{3}$' AND bank_code <> '020' AND source_error_code IS NULL)
        OR (result = 'INELIGIBLE' AND reason_code = 'NO_SALARY_ACCOUNT' AND registered IS NOT NULL AND registered = 0
            AND bank_code IS NULL AND source_error_code IS NULL)
        OR (result = 'UNAVAILABLE' AND reason_code = 'QUERY_FAILED' AND registered IS NULL AND bank_code IS NULL
            AND source_error_code IS NOT NULL AND source_error_code IN
                ('FARM_SERVICE_UNAVAILABLE', 'WORKER_NOT_FOUND', 'INVALID_PLATFORM_RESPONSE', 'SALARY_ACCOUNT_QUERY_NOT_READY'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

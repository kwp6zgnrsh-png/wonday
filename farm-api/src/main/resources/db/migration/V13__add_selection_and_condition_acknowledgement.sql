-- 기존 지원·약정·적용된 V1~V12는 수정하지 않는다. 과거 약정 버전은 추측하지 않는다.
ALTER TABLE work_assignment
    ADD COLUMN agreed_terms_version INT NULL,
    ADD CONSTRAINT ck_assignment_terms_version CHECK (agreed_terms_version IS NULL OR agreed_terms_version > 0);

-- 최초 지원은 application에 그대로 두고, 명시적 재확인만 지원·버전별 한 건 보관한다.
-- 확인한 전체 조건도 불변 JSON으로 저장해 V6에 없던 좌표·작업 내용을 현재 값으로 복원하지 않는다.
CREATE TABLE application_condition_acknowledgement (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    terms_version INT NOT NULL,
    acknowledged_at_utc DATETIME(6) NOT NULL,
    conditions_payload LONGTEXT NOT NULL,
    UNIQUE KEY uq_application_acknowledgement_version (application_id, terms_version),
    FOREIGN KEY (application_id) REFERENCES application (id),
    CONSTRAINT ck_acknowledgement_version CHECK (terms_version > 0),
    CONSTRAINT ck_acknowledgement_payload CHECK (JSON_VALID(conditions_payload))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

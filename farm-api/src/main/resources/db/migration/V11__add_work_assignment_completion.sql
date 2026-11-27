-- 기존 확정 근무의 완료 여부를 추측하지 않는다. 과거 행은 두 컬럼 모두 NULL로 보존한다.
-- completed_at_utc는 고용주의 최초 완료 요청을 처리한 서버 시각이며, 급여 지급 시각과는 다르다.
ALTER TABLE work_assignment
    ADD COLUMN completed_at_utc DATETIME(6) NULL,
    ADD COLUMN completed_by BIGINT NULL,
    ADD CONSTRAINT fk_assignment_completed_by FOREIGN KEY (completed_by) REFERENCES member (id),
    ADD CONSTRAINT ck_assignment_completion CHECK (
        (completed_at_utc IS NULL AND completed_by IS NULL) OR
        (completed_at_utc IS NOT NULL AND completed_by IS NOT NULL AND completed_by > 0)
    );
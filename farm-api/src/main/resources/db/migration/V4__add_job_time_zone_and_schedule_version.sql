-- 기존 공고의 시간대는 확인 후 보완한다. 신규 등록 API에서는 시간대를 필수로 검증한다.
ALTER TABLE job_posting ADD COLUMN time_zone VARCHAR(64) NULL;

ALTER TABLE work_schedule ADD COLUMN terms_version INT NOT NULL DEFAULT 1;

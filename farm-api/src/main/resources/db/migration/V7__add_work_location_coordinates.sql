-- 기존 공고·약정의 실제 위치는 추측할 수 없어 NULL로 남긴다. 현재 공고에서 약정으로 복사하지 않는다.
ALTER TABLE job_posting
    ADD COLUMN latitude DECIMAL(9,6) NULL,
    ADD COLUMN longitude DECIMAL(9,6) NULL,
    ADD CONSTRAINT ck_job_posting_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL) OR
        (latitude IS NOT NULL AND longitude IS NOT NULL
         AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    );

ALTER TABLE work_assignment
    ADD COLUMN agreed_latitude DECIMAL(9,6) NULL,
    ADD COLUMN agreed_longitude DECIMAL(9,6) NULL,
    ADD CONSTRAINT ck_work_assignment_coordinates CHECK (
        (agreed_latitude IS NULL AND agreed_longitude IS NULL) OR
        (agreed_latitude IS NOT NULL AND agreed_longitude IS NOT NULL
         AND agreed_latitude BETWEEN -90 AND 90 AND agreed_longitude BETWEEN -180 AND 180)
    );

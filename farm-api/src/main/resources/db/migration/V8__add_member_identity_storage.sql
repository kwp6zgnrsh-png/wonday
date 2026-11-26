-- V7은 선행 job-location의 좌표 마이그레이션이다. V7 반영 후 이 변경을 배포한다.
-- 기존 회원의 국적·번호·중복을 추측하지 않는다. 원문 컬럼과 기존 행을 그대로 보존한다.
ALTER TABLE member
    ADD COLUMN nationality_type VARCHAR(16) NULL,
    ADD COLUMN identity_type VARCHAR(32) NULL,
    ADD COLUMN identity_ciphertext VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN identity_hmac VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_member_identity_hmac (identity_hmac),
    ADD CONSTRAINT ck_member_identity_storage CHECK (
        (nationality_type IS NULL AND identity_type IS NULL AND identity_ciphertext IS NULL AND identity_hmac IS NULL)
        OR (
            nationality_type IS NOT NULL AND identity_type IS NOT NULL
            AND identity_ciphertext IS NOT NULL AND LENGTH(identity_ciphertext) > 0
            AND identity_hmac IS NOT NULL AND LENGTH(identity_hmac) = 64
            AND (
                (CAST(nationality_type AS BINARY) = CAST('DOMESTIC' AS BINARY)
                 AND CAST(identity_type AS BINARY) = CAST('RESIDENT_REGISTRATION_NUMBER' AS BINARY))
                OR
                (CAST(nationality_type AS BINARY) = CAST('FOREIGN' AS BINARY)
                 AND CAST(identity_type AS BINARY) = CAST('FOREIGN_REGISTRATION_NUMBER' AS BINARY))
            )
        )
    );
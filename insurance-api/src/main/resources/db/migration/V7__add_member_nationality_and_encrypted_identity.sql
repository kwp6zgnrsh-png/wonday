-- 기존 회원의 내/외국인 구분은 추측하지 않는다. 확인된 기존 식별정보는 V8에서 함께 이관한다.
ALTER TABLE member
    ADD COLUMN nationality_type VARCHAR(16) NULL,
    ADD COLUMN identity_type VARCHAR(32) NULL,
    ADD COLUMN identity_number_ciphertext TEXT NULL,
    ADD CONSTRAINT ck_member_nationality CHECK (nationality_type IN ('DOMESTIC', 'FOREIGN')),
    ADD CONSTRAINT ck_member_identity CHECK (
        (identity_type IS NULL AND identity_number_ciphertext IS NULL)
        OR (nationality_type IS NOT NULL AND identity_type IS NOT NULL
            AND identity_number_ciphertext IS NOT NULL AND LENGTH(identity_number_ciphertext) > 0
            AND resident_registration_number IS NULL
            AND ((nationality_type = 'DOMESTIC' AND identity_type = 'RESIDENT_REGISTRATION_NUMBER')
                OR (nationality_type = 'FOREIGN' AND identity_type = 'FOREIGN_REGISTRATION_NUMBER')))
    );

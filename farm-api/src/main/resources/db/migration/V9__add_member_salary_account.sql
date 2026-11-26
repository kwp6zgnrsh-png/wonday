-- 기존 회원·식별정보·적용된 마이그레이션은 변경하지 않는다. 계좌는 선택 등록이므로 기존 행을 채우지 않는다.
CREATE TABLE member_salary_account (
    member_id BIGINT NOT NULL PRIMARY KEY,
    bank_code CHAR(3) NOT NULL,
    account_number_ciphertext VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_holder_name VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC creation time',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC update time',
    CONSTRAINT fk_salary_account_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT ck_salary_account_bank_code CHECK (bank_code REGEXP '^[0-9]{3}$'),
    CONSTRAINT ck_salary_account_ciphertext CHECK (CHAR_LENGTH(account_number_ciphertext) > 0),
    CONSTRAINT ck_salary_account_holder CHECK (CHAR_LENGTH(TRIM(account_holder_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

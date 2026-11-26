-- V9의 계좌·기존 회원 데이터를 보존하고 계좌 관리 버전만 추가한다.
-- 버전은 회원에 남겨 계좌 해제·재등록 뒤에도 과거 화면의 요청을 구분한다.
ALTER TABLE member
    ADD COLUMN salary_account_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_member_salary_account_version CHECK (salary_account_version >= 0);

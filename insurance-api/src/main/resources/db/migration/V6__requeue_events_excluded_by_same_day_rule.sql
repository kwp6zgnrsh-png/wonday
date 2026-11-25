-- 전날 취소까지 접수 범위를 넓힌다. 이전 시간 조건으로 제외된 기록만 한 번 재판정한다.
-- 접수·이력·원본·조회 커서는 수정하지 않으며, 기존 접수 건은 서비스의 중복 검사로 보존한다.
-- 이전 코드에는 시작 이후 취소도 같은 결과로 저장돼 있으므로 새 시간 조건으로 다시 구분한다.
UPDATE platform_work_event
SET intake_processed_at = NULL,
    intake_result = NULL,
    intake_next_attempt_at = '1970-01-01 00:00:00.000000'
WHERE intake_result = 'NOT_SAME_DAY_BEFORE_START'
  AND intake_processed_at IS NOT NULL;

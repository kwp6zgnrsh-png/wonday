package com.wonday.farm.change.exception;

import org.springframework.http.HttpStatus;

public class WorkEarlyEndException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkEarlyEndException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static WorkEarlyEndException invalid() {
        return new WorkEarlyEndException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "양수인 근무 ID와 올바른 실제 종료 시각·사유를 입력해 주세요.");
    }
    public static WorkEarlyEndException invalidTime() {
        return new WorkEarlyEndException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "실제 종료 시각은 약정 시작 이상·종료 미만이고 서버 현재 시각보다 미래일 수 없습니다.");
    }
    public static WorkEarlyEndException detailTooLong() {
        return new WorkEarlyEndException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "상세 설명은 앞뒤 공백을 제거한 뒤 최대 500자까지 입력할 수 있습니다.");
    }
    public static WorkEarlyEndException notFound() {
        return new WorkEarlyEndException(HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND",
            "확정 근무를 찾을 수 없습니다.");
    }
    public static WorkEarlyEndException conflict(String code, String message) {
        return new WorkEarlyEndException(HttpStatus.CONFLICT, code, message);
    }
    public static WorkEarlyEndException deadlinePassed() {
        return conflict("EARLY_END_DEADLINE_PASSED",
            "조기 종료를 직접 기록할 수 있는 시간이 지났습니다. 실제 조기 종료 사실의 확인이 필요하면 담당자에게 문의해 주세요.");
    }
    public static WorkEarlyEndException unavailable() {
        return new WorkEarlyEndException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}

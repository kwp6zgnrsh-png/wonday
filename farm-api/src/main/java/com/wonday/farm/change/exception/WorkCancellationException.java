package com.wonday.farm.change.exception;

import org.springframework.http.HttpStatus;

public class WorkCancellationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public WorkCancellationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static WorkCancellationException invalid() {
        return new WorkCancellationException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "양수인 근무 ID와 올바른 취소 사유를 입력해 주세요.");
    }

    public static WorkCancellationException detailTooLong() {
        return new WorkCancellationException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "상세 설명은 앞뒤 공백을 제거한 뒤 최대 500자까지 입력할 수 있습니다.");
    }

    public static WorkCancellationException notFound() {
        return new WorkCancellationException(HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND",
            "확정 근무를 찾을 수 없습니다.");
    }

    public static WorkCancellationException conflict(String code, String message) {
        return new WorkCancellationException(HttpStatus.CONFLICT, code, message);
    }

    public static WorkCancellationException unavailable() {
        return new WorkCancellationException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}

package com.wonday.farm.work.exception;

import org.springframework.http.HttpStatus;

public class WorkSelectionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public WorkSelectionException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static WorkSelectionException invalid() {
        return new WorkSelectionException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "양수인 ID와 정수 조건 버전을 입력해 주세요.");
    }
    public static WorkSelectionException missing(String code, String message) {
        return new WorkSelectionException(HttpStatus.NOT_FOUND, code, message);
    }
    public static WorkSelectionException conflict(String code, String message) {
        return new WorkSelectionException(HttpStatus.CONFLICT, code, message);
    }
    public static WorkSelectionException unavailable() {
        return new WorkSelectionException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 처리할 수 없습니다.");
    }
}

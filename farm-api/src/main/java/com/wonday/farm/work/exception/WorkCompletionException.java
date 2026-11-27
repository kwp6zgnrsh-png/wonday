package com.wonday.farm.work.exception;

import org.springframework.http.HttpStatus;

public class WorkCompletionException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public WorkCompletionException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() { return status; }
	public String code() { return code; }

	public static WorkCompletionException invalidRequest() {
		return new WorkCompletionException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "확정 근무 ID를 확인해 주세요.");
	}

	public static WorkCompletionException notFound() {
		return new WorkCompletionException(HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND", "확정 근무를 찾을 수 없습니다.");
	}

	public static WorkCompletionException unavailable() {
		return new WorkCompletionException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
			"근무 정보를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
	}
}
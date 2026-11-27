package com.wonday.farm.application.exception;

import org.springframework.http.HttpStatus;

public class ApplicationException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public ApplicationException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() { return status; }
	public String code() { return code; }

	public static ApplicationException invalidRequest() {
		return new ApplicationException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
			"요청 키와 공고·일정 ID, 조건 버전, 선택한 날짜 수를 확인해 주세요.");
	}

	public static ApplicationException conflict(String code, String message) {
		return new ApplicationException(HttpStatus.CONFLICT, code, message);
	}

	public static ApplicationException unavailable() {
		return new ApplicationException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
			"지원 요청을 처리할 수 없습니다. 잠시 후 같은 요청 키로 다시 확인해 주세요.");
	}
}
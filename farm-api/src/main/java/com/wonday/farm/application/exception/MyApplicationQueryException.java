package com.wonday.farm.application.exception;

import org.springframework.http.HttpStatus;

public class MyApplicationQueryException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	private MyApplicationQueryException(HttpStatus status, String code, String message) {
		super(message); this.status = status; this.code = code;
	}
	public HttpStatus status() { return status; }
	public String code() { return code; }
	public static MyApplicationQueryException invalidRequest() {
		return new MyApplicationQueryException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
			"지원 ID, 상태 필터와 조회 개수(1~100)를 확인해 주세요.");
	}
	public static MyApplicationQueryException invalidCursor() {
		return new MyApplicationQueryException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
			"유효하지 않은 커서입니다. 같은 계정과 조회 조건으로 처음부터 조회해 주세요.");
	}
	public static MyApplicationQueryException notFound() {
		return new MyApplicationQueryException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
			"본인의 지원 내역을 찾을 수 없습니다.");
	}
}

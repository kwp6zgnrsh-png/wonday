package com.wonday.farm.insurance.exception;

import org.springframework.http.HttpStatus;

public class InsuranceApiException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	public InsuranceApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() { return status; }
	public String code() { return code; }

	public static InsuranceApiException invalidRequest() {
		return new InsuranceApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "필수 입력값과 입력 형식을 확인해 주세요.");
	}

	public static InsuranceApiException unavailable() {
		return new InsuranceApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
	}
}

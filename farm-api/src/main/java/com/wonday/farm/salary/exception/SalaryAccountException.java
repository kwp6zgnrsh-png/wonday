package com.wonday.farm.salary.exception;

import org.springframework.http.HttpStatus;

public class SalaryAccountException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	private SalaryAccountException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() { return status; }
	public String code() { return code; }

	public static SalaryAccountException invalid() {
		return new SalaryAccountException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "급여 계좌 입력값과 버전을 확인해 주세요.");
	}

	public static SalaryAccountException forbidden() {
		return new SalaryAccountException(HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED", "근로자 본인만 급여 계좌를 관리할 수 있습니다.");
	}

	public static SalaryAccountException conflict() {
		return new SalaryAccountException(HttpStatus.CONFLICT, "SALARY_ACCOUNT_VERSION_CONFLICT",
			"급여 계좌가 변경되었습니다. 최신 정보를 확인한 뒤 다시 요청해 주세요.");
	}

	public static SalaryAccountException unavailable() {
		return new SalaryAccountException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
			"현재 급여 계좌를 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
	}
}

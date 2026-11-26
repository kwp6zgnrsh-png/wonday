package com.wonday.farm.member.signup.exception;

import org.springframework.http.HttpStatus;

public class SignupException extends RuntimeException {
	private final HttpStatus status;
	private final String code;

	private SignupException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() { return status; }
	public String code() { return code; }
	public static SignupException invalid() {
		return new SignupException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "회원가입 입력값의 필수 항목과 형식을 확인해 주세요.");
	}
	public static SignupException duplicateLogin() {
		return new SignupException(HttpStatus.CONFLICT, "LOGIN_ID_ALREADY_EXISTS", "이미 사용 중인 로그인 ID입니다.");
	}
	public static SignupException duplicateIdentity() {
		return new SignupException(HttpStatus.CONFLICT, "IDENTITY_ALREADY_EXISTS", "이미 등록된 식별정보입니다.");
	}
}

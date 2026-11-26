package com.wonday.farm.job.exception;

// 입력을 수정할 수 있도록 오류 코드와 해당 항목명을 응답에 전달한다.
public class JobPostingException extends RuntimeException {
	private final String code;
	private final String field;

	public JobPostingException(String code, String message, String field) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public String code() {
		return code;
	}

	public String field() {
		return field;
	}
}

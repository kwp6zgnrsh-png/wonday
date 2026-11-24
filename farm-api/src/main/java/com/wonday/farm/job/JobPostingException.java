package com.wonday.farm.job;

// 입력을 수정할 수 있도록 오류 코드와 해당 항목명을 응답에 전달한다.
class JobPostingException extends RuntimeException {
	private final String code;
	private final String field;

	JobPostingException(String code, String message, String field) {
		super(message);
		this.code = code;
		this.field = field;
	}

	String code() {
		return code;
	}

	String field() {
		return field;
	}
}

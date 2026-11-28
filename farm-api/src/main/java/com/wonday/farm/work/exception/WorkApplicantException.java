package com.wonday.farm.work.exception;

import org.springframework.http.HttpStatus;

public class WorkApplicantException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	private WorkApplicantException(HttpStatus status, String code, String message) {
		super(message); this.status = status; this.code = code;
	}
	public HttpStatus status() { return status; }
	public String code() { return code; }
	public static WorkApplicantException invalid() {
		return new WorkApplicantException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "공고·일정·지원 ID, 필터와 조회 개수(1~100)를 확인해 주세요.");
	}
	public static WorkApplicantException cursor() {
		return new WorkApplicantException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 커서입니다. 같은 공고·날짜·필터로 처음부터 조회해 주세요.");
	}
	public static WorkApplicantException posting() {
		return new WorkApplicantException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "본인 공고를 찾을 수 없습니다.");
	}
	public static WorkApplicantException schedule() {
		return new WorkApplicantException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "해당 공고의 일정을 찾을 수 없습니다.");
	}
	public static WorkApplicantException application() {
		return new WorkApplicantException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "해당 날짜의 현재 지원 내역을 찾을 수 없습니다.");
	}
	public static WorkApplicantException contact() {
		return new WorkApplicantException(HttpStatus.FORBIDDEN, "CONTACT_ACCESS_DENIED", "현재 전체 연락처를 확인할 수 없는 지원 내역입니다.");
	}
}

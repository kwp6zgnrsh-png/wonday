package com.wonday.farm.work.exception;

import com.wonday.farm.work.api.WorkApplicantApi;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = WorkApplicantApi.class)
public class WorkApplicantErrorHandler {
	@ExceptionHandler(WorkApplicantException.class)
	ResponseEntity<Error> expected(WorkApplicantException exception) { return error(exception.status(), exception.code(), exception.getMessage()); }
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Error> invalid() { return expected(WorkApplicantException.invalid()); }
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Error> forbidden() { return error(HttpStatus.FORBIDDEN, "EMPLOYER_ROLE_REQUIRED", "고용주 본인 공고의 지원자만 조회할 수 있습니다."); }
	@ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<Error> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "지원자를 조회할 수 없습니다. 잠시 후 다시 확인해 주세요."); }
	@ExceptionHandler(Exception.class)
	ResponseEntity<Error> unexpected() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."); }
	private ResponseEntity<Error> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
	}
	record Error(String code, String message) {}
}

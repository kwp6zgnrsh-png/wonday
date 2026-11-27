package com.wonday.farm.work.exception;

import com.wonday.farm.work.api.WorkCompletionApi;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 완료 API에만 적용한다. DB 예외 메시지나 타인 소유 여부는 노출하지 않는다.
@RestControllerAdvice(assignableTypes = WorkCompletionApi.class)
public class WorkCompletionErrorHandler {
	@ExceptionHandler(WorkCompletionException.class)
	ResponseEntity<ErrorResponse> expected(WorkCompletionException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> invalidId() {
		return expected(WorkCompletionException.invalidRequest());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "EMPLOYER_ROLE_REQUIRED", "고용주 계정만 근무를 완료할 수 있습니다.");
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return expected(WorkCompletionException.unavailable());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
	}

	record ErrorResponse(String code, String message) {}
}
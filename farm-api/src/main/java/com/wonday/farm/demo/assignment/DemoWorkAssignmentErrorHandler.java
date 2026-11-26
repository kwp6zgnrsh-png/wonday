package com.wonday.farm.demo.assignment;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Profile("farm-demo")
@RestControllerAdvice(assignableTypes = DemoWorkAssignmentApi.class)
public class DemoWorkAssignmentErrorHandler {
	@ExceptionHandler(InsuranceApiException.class)
	ResponseEntity<ErrorResponse> expected(InsuranceApiException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> invalidId() {
		return expected(InsuranceApiException.invalidRequest());
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED", "근로자 본인의 약정만 확인할 수 있습니다.");
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return expected(InsuranceApiException.unavailable());
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

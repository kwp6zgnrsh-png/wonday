package com.wonday.farm.demo.application;

import com.wonday.farm.application.exception.ApplicationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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

@Profile("farm-demo")
@RestControllerAdvice(assignableTypes = {DemoJobApplicationApi.class, DemoMyApplicationFixtureApi.class})
public class DemoJobApplicationErrorHandler {
	@ExceptionHandler(ApplicationException.class)
	ResponseEntity<ErrorResponse> expected(ApplicationException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> invalidRequest() { return expected(ApplicationException.invalidRequest()); }

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED", "근로자 본인 계정으로 확인해 주세요.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> conflictingFixture() {
		return error(HttpStatus.CONFLICT, "DEMO_FIXTURE_CONFLICT", "시연 데이터 준비가 충돌했습니다. 다시 조회해 주세요.");
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() { return expected(ApplicationException.unavailable()); }

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}

	private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
	}

	record ErrorResponse(String code, String message) {}
}
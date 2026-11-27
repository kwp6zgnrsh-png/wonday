package com.wonday.farm.application.exception;

import com.wonday.farm.application.api.JobApplicationApi;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 요청값·SQL·예외 메시지 대신 정해진 오류 코드와 안내만 반환한다.
@RestControllerAdvice(assignableTypes = JobApplicationApi.class)
public class ApplicationErrorHandler {
	@ExceptionHandler(ApplicationException.class)
	ResponseEntity<ErrorResponse> expected(ApplicationException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
		MethodArgumentTypeMismatchException.class})
	ResponseEntity<ErrorResponse> invalidRequest() { return expected(ApplicationException.invalidRequest()); }

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> invalidContentType() {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_REQUEST", "application/json 형식으로 요청해 주세요.");
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return error(HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED", "근로자 계정만 공고에 지원할 수 있습니다.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> conflictingApplication() {
		return error(HttpStatus.CONFLICT, "APPLICATION_CONFLICT",
			"지원 요청이 다른 변경과 충돌했습니다. 같은 요청 키로 접수 결과를 확인해 주세요.");
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
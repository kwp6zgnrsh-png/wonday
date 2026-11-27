package com.wonday.farm.application.exception;

import com.wonday.farm.application.api.MyApplicationApi;
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

@RestControllerAdvice(assignableTypes = MyApplicationApi.class)
public class MyApplicationQueryErrorHandler {
	@ExceptionHandler(MyApplicationQueryException.class)
	ResponseEntity<Error> expected(MyApplicationQueryException exception) {
		return error(exception.status(), exception.code(), exception.getMessage());
	}
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Error> invalid() { return expected(MyApplicationQueryException.invalidRequest()); }
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Error> forbidden() {
		return error(HttpStatus.FORBIDDEN, "WORKER_ROLE_REQUIRED", "근로자 본인만 지원 내역을 조회할 수 있습니다.");
	}
	@ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<Error> unavailable() {
		return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "지원 내역을 조회할 수 없습니다. 잠시 후 다시 확인해 주세요.");
	}
	@ExceptionHandler(Exception.class)
	ResponseEntity<Error> unexpected() {
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
	}
	private ResponseEntity<Error> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code, message));
	}
	record Error(String code, String message) {}
}

package com.wonday.farm.salary.exception;

import com.wonday.farm.salary.api.SalaryAccountApi;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 계좌 관리 API에만 적용한다. 기존 가입·로그인·내부 보험 API의 오류 처리는 유지한다.
@RestControllerAdvice(assignableTypes = SalaryAccountApi.class)
public class SalaryAccountErrorHandler {
	@ExceptionHandler(SalaryAccountException.class)
	ResponseEntity<ErrorResponse> expected(SalaryAccountException exception) {
		return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
			.body(new ErrorResponse(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
		MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
		ConstraintViolationException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		// 예외 원문에 입력 계좌번호가 포함될 수 있어 고정 메시지만 반환한다.
		return expected(SalaryAccountException.invalid());
	}

	@ExceptionHandler({SalaryAccountCryptoUnavailableException.class, DataAccessResourceFailureException.class,
		TransientDataAccessException.class, CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return expected(SalaryAccountException.unavailable());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).cacheControl(CacheControl.noStore())
			.body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}

	record ErrorResponse(String code, String message) {}
}

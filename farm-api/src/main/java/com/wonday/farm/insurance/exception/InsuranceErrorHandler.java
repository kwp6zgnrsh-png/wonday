package com.wonday.farm.insurance.exception;

import com.wonday.farm.insurance.api.InsuranceIntegrationApi;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = InsuranceIntegrationApi.class)
public class InsuranceErrorHandler {
	@ExceptionHandler(InsuranceApiException.class)
	ResponseEntity<ErrorResponse> expected(InsuranceApiException exception) {
		return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
		ConstraintViolationException.class, HandlerMethodValidationException.class,
		MethodArgumentTypeMismatchException.class, HttpMediaTypeNotSupportedException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		// 검증 예외에는 개인정보 원문이 포함될 수 있어 예외 메시지·필드별 대조 결과를 노출하지 않는다.
		return expected(InsuranceApiException.invalidRequest());
	}

	@ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return expected(InsuranceApiException.unavailable());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}

	record ErrorResponse(String code, String message) {}
}

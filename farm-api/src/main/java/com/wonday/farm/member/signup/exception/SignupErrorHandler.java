package com.wonday.farm.member.signup.exception;

import java.util.Locale;

import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.signup.api.SignupApi;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SignupApi.class)
public class SignupErrorHandler {
	@ExceptionHandler(SignupException.class)
	ResponseEntity<ErrorResponse> expected(SignupException exception) {
		return ResponseEntity.status(exception.status()).body(new ErrorResponse(exception.code(), exception.getMessage()));
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
		ConstraintViolationException.class})
	ResponseEntity<ErrorResponse> invalidRequest() {
		// 검증·DB 예외 원문에는 개인정보가 포함될 수 있으므로 반환하지 않는다.
		return expected(SignupException.invalid());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException exception) {
		// flush 경합으로 실패한 트랜잭션 밖에서 제약 이름만 업무 오류로 변환한다.
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
				String name = constraint.getConstraintName();
				if (name != null) {
					String normalized = name.replace("`", "").replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
					if (normalized.equals("uq_member_login") || normalized.endsWith(".uq_member_login")) {
						return expected(SignupException.duplicateLogin());
					}
					if (normalized.equals("uq_member_identity_hmac") || normalized.endsWith(".uq_member_identity_hmac")) {
						return expected(SignupException.duplicateIdentity());
					}
				}
			}
		}
		return unexpected();
	}

	@ExceptionHandler({IdentityCryptoUnavailableException.class, SalaryAccountCryptoUnavailableException.class,
		DataAccessResourceFailureException.class, TransientDataAccessException.class,
		CannotCreateTransactionException.class, TransactionTimedOutException.class})
	ResponseEntity<ErrorResponse> unavailable() {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(new ErrorResponse("SERVICE_UNAVAILABLE", "현재 회원가입을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> unexpected() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}

	record ErrorResponse(String code, String message) {}
}

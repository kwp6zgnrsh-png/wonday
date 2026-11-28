package com.wonday.insurance.claim;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AdminClaimApi.class)
class AdminClaimErrorHandler {
	@ExceptionHandler(AdminClaimQueryException.class)
	ResponseEntity<Error> failed(AdminClaimQueryException error) {
		return ResponseEntity.status(error.getStatus()).cacheControl(CacheControl.noStore()).body(new Error(error.getCode(),error.getMessage()));
	}
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<Error> invalidQueryParameter() {
		// 서비스 진입 전에 발생한 숫자 변환 실패도 같은 응답을 사용한다. 입력값·예외 원문은 전달하지 않는다.
		return failed(AdminClaimQueryException.invalidQuery());
	}
	record Error(String code,String message) {}
}

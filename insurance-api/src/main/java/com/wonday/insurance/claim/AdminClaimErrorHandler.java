package com.wonday.insurance.claim;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminClaimApi.class)
class AdminClaimErrorHandler {
	@ExceptionHandler(AdminClaimQueryException.class)
	ResponseEntity<Error> failed(AdminClaimQueryException error) {
		return ResponseEntity.status(error.getStatus()).cacheControl(CacheControl.noStore()).body(new Error(error.getCode(),error.getMessage()));
	}
	record Error(String code,String message) {}
}

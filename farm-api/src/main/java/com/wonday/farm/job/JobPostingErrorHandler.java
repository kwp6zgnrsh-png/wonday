package com.wonday.farm.job;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 공고 등록의 입력 오류 응답을 통일한다.
@RestControllerAdvice(assignableTypes = JobPostingApi.class)
public class JobPostingErrorHandler {
	@ExceptionHandler(JobPostingException.class)
	ResponseEntity<ErrorResponse> invalidSchedule(JobPostingException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse(exception.code(), exception.getMessage(), exception.field()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidField(MethodArgumentNotValidException exception) {
		var error = exception.getBindingResult().getFieldErrors().getFirst();
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_JOB_POSTING",
			"필수 입력값과 값의 범위를 확인해 주세요.", error.getField()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> invalidJson() {
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST_BODY",
			"JSON 형식과 날짜·시간, 숫자 입력값을 확인해 주세요.", null));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("EMPLOYER_ROLE_REQUIRED",
			"고용주 계정만 공고를 등록할 수 있습니다.", null));
	}

	record ErrorResponse(String code, String message, String field) {}
}

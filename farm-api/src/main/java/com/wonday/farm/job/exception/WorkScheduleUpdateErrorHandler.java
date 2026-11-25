package com.wonday.farm.job.exception;

import com.wonday.farm.job.api.WorkScheduleUpdateApi;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = WorkScheduleUpdateApi.class)
public class WorkScheduleUpdateErrorHandler {
	@ExceptionHandler(WorkScheduleUpdateException.class)
	ResponseEntity<ErrorResponse> updateError(WorkScheduleUpdateException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ErrorResponse(exception.code(), exception.getMessage(), exception.field()));
	}

	@ExceptionHandler(JobPostingException.class)
	ResponseEntity<ErrorResponse> invalidConditions(JobPostingException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse(exception.code(), exception.getMessage(), exception.field()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> invalidField(MethodArgumentNotValidException exception) {
		var error = exception.getBindingResult().getFieldErrors().getFirst();
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_WORK_SCHEDULE",
			"필수 입력값과 값의 범위를 확인해 주세요.", error.getField()));
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class,
		MethodArgumentTypeMismatchException.class})
	ResponseEntity<ErrorResponse> invalidBody() {
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST_BODY",
			"JSON 형식과 날짜·시간, 정수 입력값을 확인해 주세요.", null));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("EMPLOYER_ROLE_REQUIRED",
			"고용주 계정만 일정을 수정할 수 있습니다.", null));
	}

	@ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
	ResponseEntity<ErrorResponse> concurrentUpdate() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("SCHEDULE_UPDATE_CONFLICT",
			"일정 저장이 충돌했습니다. 최신 일정을 조회한 후 다시 시도해 주세요.", null));
	}

	record ErrorResponse(String code, String message, String field) {}
}

package com.wonday.farm.job.exception;

import com.wonday.farm.job.api.WorkerJobPostingApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = WorkerJobPostingApi.class)
public class WorkerJobPostingErrorHandler {
	@ExceptionHandler(WorkerJobPostingException.class)
	ResponseEntity<ErrorResponse> domainError(WorkerJobPostingException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ErrorResponse(exception.code(), exception.getMessage(), exception.field()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ErrorResponse> invalidParameter(MethodArgumentTypeMismatchException exception) {
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST",
			"공고 ID와 페이지 입력 형식을 확인해 주세요.", exception.getName()));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("WORKER_ROLE_REQUIRED",
			"근로자 계정만 공고 목록과 상세를 조회할 수 있습니다.", null));
	}

	record ErrorResponse(String code, String message, String field) {}
}

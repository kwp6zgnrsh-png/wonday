package com.wonday.farm.job.exception;

import com.wonday.farm.job.api.WorkScheduleCancelApi;
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

// 모집 취소 전용 응답이다. DB 오류 상세와 타인 소유 여부는 클라이언트에 노출하지 않는다.
@RestControllerAdvice(assignableTypes = WorkScheduleCancelApi.class)
public class WorkScheduleCancelErrorHandler {
	@ExceptionHandler(WorkScheduleCancelException.class)
	ResponseEntity<ErrorResponse> cancelError(WorkScheduleCancelException exception) {
		return ResponseEntity.status(exception.status())
			.body(new ErrorResponse(exception.code(), exception.getMessage(), exception.field()));
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
			"JSON 형식과 공고·일정 ID, 조건 버전을 확인해 주세요.", null));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> forbidden() {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("EMPLOYER_ROLE_REQUIRED",
			"고용주 계정만 날짜 모집을 취소할 수 있습니다.", null));
	}

	@ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
	ResponseEntity<ErrorResponse> concurrentCancel() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("SCHEDULE_CANCEL_CONFLICT",
			"모집 취소가 다른 작업과 충돌했습니다. 최신 일정을 조회한 후 다시 시도해 주세요.", null));
	}

	record ErrorResponse(String code, String message, String field) {}
}

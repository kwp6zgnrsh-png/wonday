package com.wonday.farm.change.exception;

import com.wonday.farm.change.api.WorkCancellationApi;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 개인별 취소 API에만 적용한다. 입력 원문·DB 예외·타인 소유 여부는 응답하지 않는다.
@RestControllerAdvice(assignableTypes = WorkCancellationApi.class)
public class WorkCancellationErrorHandler {
    @ExceptionHandler(WorkCancellationException.class)
    ResponseEntity<ErrorResponse> expected(WorkCancellationException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(InsuranceApiException.class)
    ResponseEntity<ErrorResponse> recording(InsuranceApiException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid() { return expected(WorkCancellationException.invalid()); }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_REQUEST", "application/json 형식으로 요청해 주세요.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "EMPLOYER_ROLE_REQUIRED", "고용주 계정만 본인 공고의 확정 근무를 취소할 수 있습니다.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict() {
        return error(HttpStatus.CONFLICT, "WORK_STATE_CONFLICT", "다른 변경과 충돌했습니다. 최신 상태를 확인해 주세요.");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
        CannotCreateTransactionException.class, TransactionTimedOutException.class})
    ResponseEntity<ErrorResponse> unavailable() { return expected(WorkCancellationException.unavailable()); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
    }

    record ErrorResponse(String code, String message) {}
}

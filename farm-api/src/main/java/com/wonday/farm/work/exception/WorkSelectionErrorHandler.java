package com.wonday.farm.work.exception;

import com.wonday.farm.application.api.ConditionConfirmationApi;
import com.wonday.farm.work.api.WorkSelectionApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 새 두 API에만 적용한다. 입력 원문·개인정보·SQL 예외를 응답하지 않는다.
@RestControllerAdvice(assignableTypes = {WorkSelectionApi.class, ConditionConfirmationApi.class})
public class WorkSelectionErrorHandler {
    @ExceptionHandler(WorkSelectionException.class)
    ResponseEntity<ErrorResponse> expected(WorkSelectionException exception) {
        return error(exception.status(), exception.code(), exception.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> invalid() { return expected(WorkSelectionException.invalid()); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> mediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_REQUEST", "application/json 형식으로 요청해 주세요.");
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(HttpServletRequest request) {
        boolean worker = request.getRequestURI().startsWith(request.getContextPath() + "/api/me/");
        return error(HttpStatus.FORBIDDEN, worker ? "WORKER_ROLE_REQUIRED" : "EMPLOYER_ROLE_REQUIRED",
            worker ? "근로자 본인만 조건을 재확인할 수 있습니다." : "고용주 본인 공고의 지원자만 선정할 수 있습니다.");
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> conflict() {
        return error(HttpStatus.CONFLICT, "WORK_STATE_CONFLICT", "다른 변경과 충돌했습니다. 최신 상태를 확인한 뒤 다시 요청해 주세요.");
    }
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
        CannotCreateTransactionException.class, TransactionTimedOutException.class})
    ResponseEntity<ErrorResponse> unavailable() { return expected(WorkSelectionException.unavailable()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
    }
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(code, message));
    }
    record ErrorResponse(String code, String message) {}
}

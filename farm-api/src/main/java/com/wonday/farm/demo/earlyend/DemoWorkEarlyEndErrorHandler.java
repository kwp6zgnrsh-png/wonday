package com.wonday.farm.demo.earlyend;

import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.work.exception.WorkCompletionException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Profile("farm-demo")
@RestControllerAdvice(assignableTypes=DemoWorkEarlyEndApi.class)
public class DemoWorkEarlyEndErrorHandler {
    @ExceptionHandler(WorkEarlyEndException.class)
    ResponseEntity<Error> earlyEnd(WorkEarlyEndException exception) {
        return error(exception.status(),exception.code(),exception.getMessage());
    }
    @ExceptionHandler(InsuranceApiException.class)
    ResponseEntity<Error> recording(InsuranceApiException exception) {
        return error(exception.status(),exception.code(),exception.getMessage());
    }
    @ExceptionHandler(WorkCompletionException.class)
    ResponseEntity<Error> completion(WorkCompletionException exception) {
        return error(exception.status(),exception.code(),exception.getMessage());
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Error> denied() {
        return error(HttpStatus.FORBIDDEN,"EMPLOYER_ROLE_REQUIRED","고용주 본인 계정으로 시연 데이터를 준비해 주세요.");
    }
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<Error> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_UNAVAILABLE","시연 데이터를 준비할 수 없습니다.");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_SERVER_ERROR","서버 내부 오류가 발생했습니다.");
    }
    private ResponseEntity<Error> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code,message));
    }
    record Error(String code,String message) {}
}

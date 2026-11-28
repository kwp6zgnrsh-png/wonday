package com.wonday.farm.demo.selection;

import com.wonday.farm.work.exception.WorkSelectionException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.*;

@Profile("farm-demo")
@RestControllerAdvice(assignableTypes=DemoWorkSelectionApi.class)
public class DemoWorkSelectionErrorHandler {
    @ExceptionHandler(WorkSelectionException.class)
    ResponseEntity<Error> expected(WorkSelectionException e){return error(e.status(),e.code(),e.getMessage());}
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Error> denied(){return error(HttpStatus.FORBIDDEN,"FARM_ROLE_REQUIRED","농업 계정으로 로그인해 주세요.");}
    @ExceptionHandler({DataAccessException.class,CannotCreateTransactionException.class})
    ResponseEntity<Error> unavailable(){return error(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_UNAVAILABLE","시연 데이터를 준비하거나 조회할 수 없습니다.");}
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(){return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_SERVER_ERROR","서버 내부 오류가 발생했습니다.");}
    private ResponseEntity<Error> error(HttpStatus status,String code,String message){
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Error(code,message));
    }
    record Error(String code,String message){}
}

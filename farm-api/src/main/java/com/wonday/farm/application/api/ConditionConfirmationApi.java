package com.wonday.farm.application.api;

import com.wonday.farm.application.dto.ConditionConfirmationResponse;
import com.wonday.farm.application.service.ConditionConfirmationService;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.validation.SelectionInput;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/me/applications/{applicationId}/condition-confirmation", produces = "application/json;charset=UTF-8")
public class ConditionConfirmationApi {
    private final ConditionConfirmationService service;
    public ConditionConfirmationApi(ConditionConfirmationService service) { this.service = service; }

    // 본문 형식은 매핑 이후 메시지 변환기가 검사한다.
    // 지원하지 않는 형식도 이 API 전용 오류 처리기의 415 JSON·no-store 응답을 사용하게 한다.
    @PostMapping
    public ResponseEntity<ConditionConfirmationResponse> confirm(@AuthenticationPrincipal FarmPrincipal worker,
            @PathVariable String applicationId, @Valid @RequestBody TermsVersionRequest request) {
        if (worker == null) throw new AccessDeniedException("Worker session required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.confirm(worker.memberId(), SelectionInput.id(applicationId), request));
    }
}

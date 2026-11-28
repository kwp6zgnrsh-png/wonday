package com.wonday.farm.change.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.dto.WorkCancellationResponse;
import com.wonday.farm.change.service.WorkCancellationService;
import com.wonday.farm.change.validation.CancellationInput;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/work-assignments", produces = "application/json;charset=UTF-8")
public class WorkCancellationApi {
    private final WorkCancellationService service;

    public WorkCancellationApi(WorkCancellationService service) { this.service = service; }

    // 고용주 ID와 취소 시각은 받지 않는다. 기존 로그인 세션·CSRF 보호를 그대로 사용한다.
    // consumes를 매핑에 두지 않아 잘못된 본문 형식도 전용 오류 처리기의 415 JSON으로 응답한다.
    @PostMapping("/{assignmentId}/cancellation")
    public ResponseEntity<WorkCancellationResponse> cancel(@AuthenticationPrincipal FarmPrincipal employer,
            @PathVariable("assignmentId") String assignmentId, @RequestBody WorkCancellationRequest request) {
        if (employer == null) throw new AccessDeniedException("Employer session required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.cancel(employer.memberId(), CancellationInput.id(assignmentId), request));
    }
}

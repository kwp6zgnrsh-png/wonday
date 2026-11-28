package com.wonday.farm.work.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.dto.WorkSelectionResponse;
import com.wonday.farm.work.service.WorkSelectionService;
import com.wonday.farm.work.validation.SelectionInput;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications/{applicationId}/selection",
    produces = "application/json;charset=UTF-8")
public class WorkSelectionApi {
    private final WorkSelectionService service;
    public WorkSelectionApi(WorkSelectionService service) { this.service = service; }

    // 본문 형식은 매핑 이후 메시지 변환기가 검사한다.
    // 지원하지 않는 형식도 이 API 전용 오류 처리기의 415 JSON·no-store 응답을 사용하게 한다.
    @PostMapping
    public ResponseEntity<WorkSelectionResponse> select(@AuthenticationPrincipal FarmPrincipal employer,
            @PathVariable String jobPostingId, @PathVariable String workScheduleId, @PathVariable String applicationId,
            @Valid @RequestBody TermsVersionRequest request) {
        if (employer == null) throw new AccessDeniedException("Employer session required");
        var result = service.select(employer.memberId(), SelectionInput.id(jobPostingId),
            SelectionInput.id(workScheduleId), SelectionInput.id(applicationId), request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore()).body(result.body());
    }
}

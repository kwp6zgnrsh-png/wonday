package com.wonday.farm.change.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.dto.WorkEarlyEndResponse;
import com.wonday.farm.change.service.WorkEarlyEndService;
import com.wonday.farm.change.validation.EarlyEndInput;
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
public class WorkEarlyEndApi {
    private final WorkEarlyEndService service;
    public WorkEarlyEndApi(WorkEarlyEndService service) { this.service = service; }

    // 형식 검사는 매핑 이후 변환기에서 수행해 415도 전용 JSON 오류로 반환한다.
    @PostMapping("/{assignmentId}/early-end")
    public ResponseEntity<WorkEarlyEndResponse> end(@AuthenticationPrincipal FarmPrincipal employer,
            @PathVariable("assignmentId") String assignmentId, @RequestBody WorkEarlyEndRequest request) {
        if (employer == null) throw new AccessDeniedException("Employer session required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.end(employer.memberId(), EarlyEndInput.id(assignmentId), request));
    }
}

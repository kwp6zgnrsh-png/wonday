package com.wonday.farm.demo.cancellation;

import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("farm-demo")
@RestController
public class DemoWorkCancellationApi {
    private final DemoWorkCancellationFixtureService service;

    public DemoWorkCancellationApi(DemoWorkCancellationFixtureService service) { this.service = service; }

    @GetMapping(value = "/demo/work-cancellation", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ClassPathResource("demo/farm-work-cancellation.html"));
    }

    // 이전 지원·선정 과정을 수동 반복하지 않고, 본인 시연용 확정 근무를 준비한다. CSRF는 필요하다.
    @PostMapping(value = "/demo/api/work-cancellation/fixtures", produces = "application/json;charset=UTF-8")
    public ResponseEntity<DemoWorkCancellationFixtureService.FixtureResponse> prepare(
            @AuthenticationPrincipal FarmPrincipal employer) {
        if (employer == null) throw new AccessDeniedException("Employer session required");
        var result = service.prepare(employer.memberId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .cacheControl(CacheControl.noStore()).body(result);
    }
}

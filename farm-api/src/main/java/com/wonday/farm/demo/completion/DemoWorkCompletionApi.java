package com.wonday.farm.demo.completion;

import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 데모용 읽기 경로만 추가한다. 완료 쓰기는 기존 운영 API의 세션·CSRF 검사를 그대로 거친다.
@Profile("farm-demo")
@RestController
public class DemoWorkCompletionApi {
	private final DemoWorkCompletionQueryService service;

	public DemoWorkCompletionApi(DemoWorkCompletionQueryService service) { this.service = service; }

	@GetMapping(value = "/demo/work-completion", produces = "text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new ClassPathResource("demo/farm-work-completion.html"));
	}

	@GetMapping(value = "/demo/api/employer/work-assignments", produces = "application/json;charset=UTF-8")
	public ResponseEntity<DemoWorkCompletionQueryService.Options> list(@AuthenticationPrincipal FarmPrincipal employer) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(employerId(employer)));
	}

	@GetMapping(value = "/demo/api/employer/work-assignments/{assignmentId}", produces = "application/json;charset=UTF-8")
	public ResponseEntity<DemoWorkCompletionQueryService.AssignmentDetail> detail(
			@AuthenticationPrincipal FarmPrincipal employer, @PathVariable("assignmentId") long assignmentId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.detail(employerId(employer), assignmentId));
	}

	private static long employerId(FarmPrincipal employer) {
		if (employer == null) throw new AccessDeniedException("Employer session required");
		return employer.memberId();
	}
}
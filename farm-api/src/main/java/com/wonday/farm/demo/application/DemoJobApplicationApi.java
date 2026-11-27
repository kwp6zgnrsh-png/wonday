package com.wonday.farm.demo.application;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 실제 지원·재지원은 운영 POST를 사용한다. 이 컨트롤러는 demo 준비·본인 이력 조회만 제공한다.
@Profile("farm-demo")
@RestController
public class DemoJobApplicationApi {
	private final DemoJobApplicationService service;

	public DemoJobApplicationApi(DemoJobApplicationService service) { this.service = service; }

	@GetMapping(value = "/demo/job-apply", produces = "text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new ClassPathResource("demo/farm-job-apply.html"));
	}

	@GetMapping(value = "/demo/api/applications/postings/{jobPostingId}/history", produces = "application/json;charset=UTF-8")
	public ResponseEntity<DemoJobApplicationService.HistoryResponse> history(
			@AuthenticationPrincipal FarmPrincipal worker, @PathVariable("jobPostingId") long postingId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(workerId(worker), postingId));
	}

	@PostMapping(value = "/demo/api/applications/fixtures", produces = "application/json;charset=UTF-8")
	public ResponseEntity<DemoJobApplicationService.FixtureResponse> fixture(@AuthenticationPrincipal FarmPrincipal worker) {
		var result = service.prepareFixture(workerId(worker));
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.cacheControl(CacheControl.noStore()).body(result);
	}

	private static long workerId(FarmPrincipal worker) {
		if (worker == null) throw new AccessDeniedException("Worker session required");
		return worker.memberId();
	}
}
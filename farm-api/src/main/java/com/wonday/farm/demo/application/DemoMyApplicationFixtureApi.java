package com.wonday.farm.demo.application;

import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("farm-demo")
@RestController
public class DemoMyApplicationFixtureApi {
	private final DemoMyApplicationFixtureService service;
	public DemoMyApplicationFixtureApi(DemoMyApplicationFixtureService service) { this.service = service; }

	// POST + 근로자 세션 + 기존 CSRF. 입력으로 다른 회원·공고·근무를 선택할 수 없다.
	@PostMapping(value = "/demo/api/my-applications/fixtures", produces = "application/json;charset=UTF-8")
	public ResponseEntity<DemoMyApplicationFixtureService.FixtureResponse> prepare(@AuthenticationPrincipal FarmPrincipal worker) {
		if (worker == null) throw new AccessDeniedException("Worker session required");
		var result = service.prepare(worker.memberId());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.cacheControl(CacheControl.noStore()).body(result);
	}
}

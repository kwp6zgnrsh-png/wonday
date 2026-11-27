package com.wonday.farm.work.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.WorkCompletionResponse;
import com.wonday.farm.work.service.WorkCompletionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/work-assignments", produces = "application/json;charset=UTF-8")
public class WorkCompletionApi {
	private final WorkCompletionService service;

	public WorkCompletionApi(WorkCompletionService service) { this.service = service; }

	// 요청 본문 없이 개인별 확정 근무를 완료한다. 처리자와 완료 시각은 서버가 정한다.
	@PostMapping("/{assignmentId}/completion")
	public ResponseEntity<WorkCompletionResponse> complete(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable("assignmentId") long assignmentId) {
		if (employer == null) throw new AccessDeniedException("Employer session required");
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(service.complete(employer.memberId(), assignmentId));
	}
}
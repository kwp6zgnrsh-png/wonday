package com.wonday.farm.job.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.UpdateWorkScheduleRequest;
import com.wonday.farm.job.dto.UpdatedWorkScheduleResponse;
import com.wonday.farm.job.service.WorkScheduleUpdateService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/job-postings/{jobPostingId}/schedules", produces = "application/json;charset=UTF-8")
public class WorkScheduleUpdateApi {
	private final WorkScheduleUpdateService service;

	public WorkScheduleUpdateApi(WorkScheduleUpdateService service) { this.service = service; }

	// Content-Type 불일치도 본문 읽기 단계에서 거부해 이 API의 JSON 오류 처리기로 연결한다.
	@PutMapping("/{workScheduleId}")
	public UpdatedWorkScheduleResponse update(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable long jobPostingId, @PathVariable long workScheduleId,
			@Valid @RequestBody UpdateWorkScheduleRequest request) {
		return service.update(employer.memberId(), jobPostingId, workScheduleId, request);
	}
}

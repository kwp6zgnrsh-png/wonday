package com.wonday.farm.job.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.CancelWorkScheduleRequest;
import com.wonday.farm.job.dto.CancelledWorkScheduleResponse;
import com.wonday.farm.job.service.WorkScheduleCancelService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/job-postings/{jobPostingId}/schedules", produces = "application/json;charset=UTF-8")
public class WorkScheduleCancelApi {
	private final WorkScheduleCancelService service;

	public WorkScheduleCancelApi(WorkScheduleCancelService service) { this.service = service; }

	// 물리 삭제가 아닌 모집 상태 전환이다. 고용주 ID와 취소 시각은 서버가 결정한다.
	@PostMapping("/{workScheduleId}/cancel")
	public CancelledWorkScheduleResponse cancel(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable long jobPostingId, @PathVariable long workScheduleId,
			@Valid @RequestBody CancelWorkScheduleRequest request) {
		return service.cancel(employer.memberId(), jobPostingId, workScheduleId, request);
	}
}

package com.wonday.farm.job.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.WorkerJobPostingResponse;
import com.wonday.farm.job.dto.WorkerJobPostingsResponse;
import com.wonday.farm.job.service.WorkerJobPostingQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/job-postings", produces = "application/json;charset=UTF-8")
public class WorkerJobPostingApi {
	private final WorkerJobPostingQueryService service;

	public WorkerJobPostingApi(WorkerJobPostingQueryService service) { this.service = service; }

	@GetMapping
	public WorkerJobPostingsResponse list(@AuthenticationPrincipal FarmPrincipal worker,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return service.list(worker.memberId(), page, size);
	}

	@GetMapping("/{jobPostingId}")
	public WorkerJobPostingResponse detail(@AuthenticationPrincipal FarmPrincipal worker, @PathVariable long jobPostingId) {
		return service.detail(worker.memberId(), jobPostingId);
	}
}

package com.wonday.farm.job.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.CreateJobPostingResponse;
import com.wonday.farm.job.service.JobPostingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-postings")
public class JobPostingApi {
	private final JobPostingService service;

	public JobPostingApi(JobPostingService service) {
		this.service = service;
	}

	@PostMapping(consumes = "application/json", produces = "application/json;charset=UTF-8")
	@ResponseStatus(HttpStatus.CREATED)
	public CreateJobPostingResponse create(@AuthenticationPrincipal FarmPrincipal employer,
			@Valid @RequestBody CreateJobPostingRequest request) {
		// 요청 본문에서 소유자를 선택할 수 없으며 로그인한 회원이 공고의 소유자가 된다.
		return service.create(employer.memberId(), request);
	}
}

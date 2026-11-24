package com.wonday.farm.job;

import com.wonday.farm.auth.FarmPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MyJobPostingApi {
	private final JobPostingQueryService service;

	public MyJobPostingApi(JobPostingQueryService service) {
		this.service = service;
	}

	@GetMapping(value = "/api/job-postings/mine", produces = "application/json;charset=UTF-8")
	public MyJobPostingsResponse mine(@AuthenticationPrincipal FarmPrincipal employer) {
		// 요청 파라미터로 소유자를 선택할 수 없으며, 세션의 회원 ID만 조회 조건에 사용한다.
		return service.findMine(employer.memberId());
	}
}

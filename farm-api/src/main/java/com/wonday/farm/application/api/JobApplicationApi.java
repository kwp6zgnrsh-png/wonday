package com.wonday.farm.application.api;

import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.dto.ApplyJobPostingResponse;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.service.JobApplicationService;
import com.wonday.farm.auth.FarmPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/job-postings/{jobPostingId}/applications", produces = "application/json;charset=UTF-8")
public class JobApplicationApi {
	private final JobApplicationService service;

	public JobApplicationApi(JobApplicationService service) { this.service = service; }

	@PostMapping(consumes = "application/json")
	public ResponseEntity<ApplyJobPostingResponse> apply(@AuthenticationPrincipal FarmPrincipal worker,
			@PathVariable("jobPostingId") long postingId, @RequestHeader HttpHeaders headers,
			@Valid @RequestBody ApplyJobPostingRequest request) {
		if (worker == null) throw new AccessDeniedException("Worker session required");
		var keys = headers.get("Idempotency-Key");
		if (keys == null || keys.size() != 1) throw ApplicationException.invalidRequest();
		var result = service.apply(worker.memberId(), postingId, keys.getFirst(), request);
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore()).body(result.body());
	}
}
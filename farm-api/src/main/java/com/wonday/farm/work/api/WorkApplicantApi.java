package com.wonday.farm.work.api;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.WorkApplicantResponses;
import com.wonday.farm.work.exception.WorkApplicantException;
import com.wonday.farm.work.service.WorkApplicantQueryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications", produces = "application/json;charset=UTF-8")
public class WorkApplicantApi {
	private final WorkApplicantQueryService service;
	public WorkApplicantApi(WorkApplicantQueryService service) { this.service = service; }
	@GetMapping
	public ResponseEntity<WorkApplicantResponses.Page> list(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable String jobPostingId, @PathVariable String workScheduleId,
			@RequestParam MultiValueMap<String, String> query) {
		return ok(service.list(employerId(employer), id(jobPostingId), id(workScheduleId),
			parameter(query, "status"), parameter(query, "limit"), parameter(query, "cursor")));
	}
	@GetMapping("/{applicationId}")
	public ResponseEntity<WorkApplicantResponses.Detail> detail(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable String jobPostingId, @PathVariable String workScheduleId, @PathVariable String applicationId) {
		return ok(service.detail(employerId(employer), id(jobPostingId), id(workScheduleId), id(applicationId)));
	}
	@GetMapping("/{applicationId}/contact")
	public ResponseEntity<WorkApplicantResponses.Contact> contact(@AuthenticationPrincipal FarmPrincipal employer,
			@PathVariable String jobPostingId, @PathVariable String workScheduleId, @PathVariable String applicationId) {
		return ok(service.contact(employerId(employer), id(jobPostingId), id(workScheduleId), id(applicationId)));
	}
	private static long employerId(FarmPrincipal employer) {
		if (employer == null) throw new AccessDeniedException("Employer session required");
		return employer.memberId();
	}
	private static long id(String value) {
		try {
			if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw WorkApplicantException.invalid();
			return Long.parseLong(value);
		} catch (NumberFormatException exception) { throw WorkApplicantException.invalid(); }
	}
	private static String parameter(MultiValueMap<String, String> query, String name) {
		var values = query.get(name);
		if (values == null) return null;
		if (values.size() != 1) throw WorkApplicantException.invalid();
		return values.getFirst();
	}
	private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}

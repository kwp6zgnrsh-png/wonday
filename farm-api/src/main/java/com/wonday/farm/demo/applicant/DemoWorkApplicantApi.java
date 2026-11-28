package com.wonday.farm.demo.applicant;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("farm-demo")
@RestController
public class DemoWorkApplicantApi {
	private final DemoWorkApplicantFixtureService service;
	public DemoWorkApplicantApi(DemoWorkApplicantFixtureService service) { this.service=service; }
	@GetMapping(value="/demo/work-applicants",produces="text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource("demo/farm-work-applicants.html"));
	}
	@PostMapping(value="/demo/api/work-applicants/fixtures",produces="application/json;charset=UTF-8")
	public ResponseEntity<DemoWorkApplicantFixtureService.FixtureResponse> prepare(@AuthenticationPrincipal FarmPrincipal employer) {
		if(employer==null) throw new AccessDeniedException("Employer session required");
		var result=service.prepare(employer.memberId());
		return ResponseEntity.status(result.created()?HttpStatus.CREATED:HttpStatus.OK).cacheControl(CacheControl.noStore()).body(result);
	}
}

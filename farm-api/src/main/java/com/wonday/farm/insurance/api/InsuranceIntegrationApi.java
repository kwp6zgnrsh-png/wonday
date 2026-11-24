package com.wonday.farm.insurance.api;

import com.wonday.farm.insurance.dto.VerifiedWorkerResponse;
import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.dto.WorkChangesResponse;
import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.insurance.service.WorkAssignmentQueryService;
import com.wonday.farm.insurance.service.WorkChangeQueryService;
import com.wonday.farm.insurance.service.WorkerVerificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/internal/insurance", produces = "application/json;charset=UTF-8")
public class InsuranceIntegrationApi {
	private final WorkerVerificationService verification;
	private final WorkAssignmentQueryService assignments;
	private final WorkChangeQueryService changes;

	public InsuranceIntegrationApi(WorkerVerificationService verification, WorkAssignmentQueryService assignments,
			WorkChangeQueryService changes) {
		this.verification = verification;
		this.assignments = assignments;
		this.changes = changes;
	}

	@PostMapping(value = "/worker-verifications", consumes = "application/json")
	public VerifiedWorkerResponse verify(@Valid @RequestBody WorkerVerificationRequest request) {
		return verification.verify(request);
	}

	@GetMapping("/workers/{workerId}/work-assignments/{assignmentId}")
	public WorkAssignmentResponse assignment(@PathVariable long workerId, @PathVariable long assignmentId) {
		return assignments.find(workerId, assignmentId);
	}

	@GetMapping("/workers/{workerId}/work-changes")
	public WorkChangesResponse changes(@PathVariable long workerId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "100") int limit) {
		return changes.poll(workerId, cursor, limit);
	}
}

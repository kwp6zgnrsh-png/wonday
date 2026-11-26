package com.wonday.farm.demo.assignment;

import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.service.WorkAssignmentQueryService;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 보험 토큰을 브라우저에 전달하지 않고 본인 약정의 응답 형태를 확인하는 데모 전용 경로다.
// 보험 HTTP 인증을 대신 검증하지 않으며, 임의 workerId·URL·토큰을 입력받지 않는다.
@Profile("farm-demo")
@RestController
@RequestMapping(value = "/demo/api/work-assignments", produces = "application/json;charset=UTF-8")
public class DemoWorkAssignmentApi {
	private final MemberRepository members;
	private final DemoWorkAssignmentRepository repository;
	private final WorkAssignmentQueryService assignments;

	public DemoWorkAssignmentApi(MemberRepository members, DemoWorkAssignmentRepository repository,
			WorkAssignmentQueryService assignments) {
		this.members = members;
		this.repository = repository;
		this.assignments = assignments;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public ResponseEntity<Options> list(@AuthenticationPrincipal FarmPrincipal worker) {
		requireWorker(worker.memberId());
		var options = repository.findMine(worker.memberId()).stream().map(row ->
			new AssignmentOption(row.getAssignmentId(), row.getAgreedWorkDate(), row.getAgreedWorkLocation(),
				row.getAgreedLatitude() == null && row.getAgreedLongitude() == null)).toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Options(options));
	}

	@GetMapping("/{assignmentId}")
	public ResponseEntity<WorkAssignmentResponse> detail(@AuthenticationPrincipal FarmPrincipal worker,
			@PathVariable long assignmentId) {
		requireWorker(worker.memberId());
		// 보험 상세와 같은 서비스로 약정 좌표와 소유자를 확인한다. 현재 공고 좌표는 조회하지 않는다.
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(assignments.find(worker.memberId(), assignmentId));
	}

	private void requireWorker(long workerId) {
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));
	}

	public record Options(List<AssignmentOption> assignments) {}
	public record AssignmentOption(long assignmentId, LocalDate agreedWorkDate, String agreedWorkLocation,
		boolean coordinatesMissing) {}
}

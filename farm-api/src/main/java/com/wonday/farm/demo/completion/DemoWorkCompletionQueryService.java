package com.wonday.farm.demo.completion;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.insurance.service.InsuranceTime;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.exception.WorkCompletionException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
@Transactional(readOnly = true)
public class DemoWorkCompletionQueryService {
	private final MemberRepository members;
	private final DemoWorkCompletionRepository repository;

	public DemoWorkCompletionQueryService(MemberRepository members, DemoWorkCompletionRepository repository) {
		this.members = members;
		this.repository = repository;
	}

	public Options list(long employerId) {
		requireEmployer(employerId);
		return new Options(repository.findForEmployer(employerId).stream().map(row ->
			new AssignmentOption(row.getAssignmentId(), row.getJobPostingId(), row.getTitle(),
				row.getWorkerName(), row.getAgreedWorkDate())).toList());
	}

	public AssignmentDetail detail(long employerId, long assignmentId) {
		if (assignmentId <= 0) throw WorkCompletionException.invalidRequest();
		requireEmployer(employerId);
		var row = repository.findDetailForEmployer(employerId, assignmentId)
			.orElseThrow(WorkCompletionException::notFound);
		// POST 응답을 재사용하지 않고 DB에서 약정과 완료 시각을 다시 읽어 확인한다.
		var zone = InsuranceTime.zone(row.getAgreedTimeZone());
		return new AssignmentDetail(row.getAssignmentId(), row.getJobPostingId(), row.getTitle(),
			row.getWorkerName(), row.getAgreedWorkDate(), row.getAgreedTimeZone(),
			InsuranceTime.local(row.getAgreedEndAt(), zone),
			row.getCompletedAtUtc() == null ? null : InsuranceTime.utc(row.getCompletedAtUtc(), zone),
			row.getChangeType());
	}

	private void requireEmployer(long employerId) {
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
	}

	public record Options(List<AssignmentOption> assignments) {}
	public record AssignmentOption(long assignmentId, long jobPostingId, String title, String workerName,
		LocalDate agreedWorkDate) {}
	public record AssignmentDetail(long assignmentId, long jobPostingId, String title, String workerName,
		LocalDate agreedWorkDate, String agreedTimeZone, OffsetDateTime agreedEndAt,
		OffsetDateTime completedAt, String changeType) {}
}
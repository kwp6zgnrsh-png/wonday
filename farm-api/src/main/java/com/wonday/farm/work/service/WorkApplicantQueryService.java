package com.wonday.farm.work.service;

import java.util.Set;
import com.wonday.farm.application.service.ApplicationConditionMapper;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.WorkApplicantResponses;
import com.wonday.farm.work.exception.WorkApplicantException;
import com.wonday.farm.work.repository.WorkApplicantRepository;
import com.wonday.farm.work.validation.ApplicantPhone;
import com.wonday.farm.work.validation.WorkApplicantCursor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WorkApplicantQueryService {
	private static final Set<String> FILTERS = Set.of("ALL", "PENDING", "SELECTED", "NOT_SELECTED");
	private final MemberRepository members;
	private final WorkApplicantRepository repository;
	private final WorkScheduleRepository schedules;
	private final ApplicationConditionMapper mapper;
	public WorkApplicantQueryService(MemberRepository members, WorkApplicantRepository repository,
			WorkScheduleRepository schedules, ApplicationConditionMapper mapper) {
		this.members = members; this.repository = repository; this.schedules = schedules; this.mapper = mapper;
	}

	public WorkApplicantResponses.Page list(long employerId, long postingId, long scheduleId,
			String status, String limit, String cursor) {
		var date = scope(employerId, postingId, scheduleId);
		String filter = status == null ? "PENDING" : status;
		if (!FILTERS.contains(filter)) throw WorkApplicantException.invalid();
		int size = size(limit);
		Long anchorId = WorkApplicantCursor.decode(cursor, employerId, postingId, scheduleId, filter);
		var anchor = anchorId == null ? null : repository.findOwnedAnchor(employerId, postingId, scheduleId, anchorId)
			.orElseThrow(WorkApplicantException::cursor);
		var rows = repository.findCurrent(employerId, postingId, scheduleId, filter,
			anchor == null ? null : anchor.getSortAppliedAt(), anchorId, size + 1);
		boolean hasMore = rows.size() > size;
		var visible = rows.subList(0, Math.min(size, rows.size()));
		String next = hasMore ? WorkApplicantCursor.encode(employerId, postingId, scheduleId, filter,
			visible.getLast().getApplicationId()) : null;
		long confirmed = schedules.countOccupyingAssignments(scheduleId);
		return new WorkApplicantResponses.Page(postingId, scheduleId, date.getWorkDate(), date.getRecruitmentStatus(),
			date.getHeadcount(), confirmed, Math.max((long) date.getHeadcount() - confirmed, 0),
			visible.stream().map(this::item).toList(), next, hasMore);
	}

	public WorkApplicantResponses.Detail detail(long employerId, long postingId, long scheduleId, long applicationId) {
		scope(employerId, postingId, scheduleId);
		if (applicationId <= 0) throw WorkApplicantException.invalid();
		var row = repository.findCurrentOwned(employerId, postingId, scheduleId, applicationId)
			.orElseThrow(WorkApplicantException::application);
		var item = item(row); var detail = mapper.detail(row);
		return new WorkApplicantResponses.Detail(item.applicationId(), item.workerId(), item.name(),
			item.maskedPhone(), item.phoneState(), item.applicationRound(), item.status(), item.canViewContact(),
			item.appliedAt(), item.legacyAppliedAt(), item.appliedTimeBasis(), item.appliedTermsVersion(), item.latestTermsVersion(),
			item.conditionComparisonStatus(), detail.appliedConditions(), detail.latestConditions(), detail.changedFields(),
			detail.unavailableComparisonFields(), detail.comparisonComplete(), detail.assignment());
	}

	// 목록에서 받은 허용 여부를 신뢰하지 않고 요청 시점의 상태와 최신 회원 연락처를 다시 읽는다.
	@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
	public WorkApplicantResponses.Contact contact(long employerId, long postingId, long scheduleId, long applicationId) {
		scope(employerId, postingId, scheduleId);
		if (applicationId <= 0) throw WorkApplicantException.invalid();
		var row = repository.findContact(employerId, postingId, scheduleId, applicationId)
			.orElseThrow(WorkApplicantException::application);
		if (row.getCurrentFlag() != 1 || !contactStatus(row.getStatus())) throw WorkApplicantException.contact();
		return new WorkApplicantResponses.Contact(row.getApplicationId(), row.getWorkerId(), row.getWorkerName(),
			ApplicantPhone.normalized(row.getPhone()), ApplicantPhone.state(row.getPhone()));
	}

	private WorkApplicantRepository.Schedule scope(long employerId, long postingId, long scheduleId) {
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		if (postingId <= 0 || scheduleId <= 0) throw WorkApplicantException.invalid();
		repository.findOwnedPosting(employerId, postingId).orElseThrow(WorkApplicantException::posting);
		// 모집 상태·예정 시각으로 제한하지 않는다. 지난 날짜와 취소된 모집도 본인 공고이면 조회한다.
		return repository.findOwnedSchedule(employerId, postingId, scheduleId).orElseThrow(WorkApplicantException::schedule);
	}
	private WorkApplicantResponses.Item item(WorkApplicantRepository.Row row) {
		var base = mapper.item(row);
		return new WorkApplicantResponses.Item(row.getApplicationId(), row.getWorkerId(), row.getWorkerName(),
			row.getMaskedPhone(), row.getPhoneState(), row.getApplicationRound(), row.getApplicationStatus(),
			base.appliedAt(), base.legacyAppliedAt(), base.appliedTimeBasis(), base.appliedTermsVersion(), base.latestTermsVersion(),
			base.conditionComparisonStatus(), row.getCurrentFlag() == 1 && contactStatus(row.getApplicationStatus()),
			row.getAssignmentId(), row.getDisplayStatus());
	}
	private static boolean contactStatus(String status) { return "PENDING".equals(status) || "SELECTED".equals(status); }
	private static int size(String value) {
		if (value == null) return 20;
		if (!value.matches("[0-9]{1,3}")) throw WorkApplicantException.invalid();
		int size = Integer.parseInt(value);
		if (size < 1 || size > 100) throw WorkApplicantException.invalid();
		return size;
	}
}

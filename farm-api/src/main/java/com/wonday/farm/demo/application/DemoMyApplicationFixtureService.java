package com.wonday.farm.demo.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.service.WorkCompletionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
public class DemoMyApplicationFixtureService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final WorkScheduleRevisionRepository revisions;
	private final DemoJobApplicationRepository fixtureLookup;
	private final DemoMyApplicationFixtureRepository fixtures;
	private final ApplicationRequestLockRepository locks;
	private final WorkChangeRecorder changes;
	private final WorkCompletionService completion;
	private final Clock clock;

	public DemoMyApplicationFixtureService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, WorkScheduleRevisionRepository revisions,
			DemoJobApplicationRepository fixtureLookup, DemoMyApplicationFixtureRepository fixtures,
			ApplicationRequestLockRepository locks, WorkChangeRecorder changes, WorkCompletionService completion,
			@Qualifier("jobClock") Clock clock) {
		this.members = members; this.postings = postings; this.schedules = schedules; this.revisions = revisions;
		this.fixtureLookup = fixtureLookup; this.fixtures = fixtures; this.locks = locks;
		this.changes = changes; this.completion = completion; this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public FixtureResponse prepare(long workerId) {
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));
		// 같은 근로자의 연타·동시 준비와 APP-02 요청을 직렬화한다.
		locks.ensureExists(workerId);
		locks.lockWorker(workerId).orElseThrow(ApplicationException::unavailable);
		var employer = members.findByLoginId("demo-employer")
			.filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new ApplicationException(HttpStatus.CONFLICT, "DEMO_EMPLOYER_UNAVAILABLE",
				"farm-demo의 demo-employer 고용주 계정을 확인해 주세요."));
		var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		var today = LocalDate.ofInstant(now, ZoneOffset.ofHours(9));
		String marker = "[APP-04 시연·근로자 " + workerId + "·" + today + "]";
		var existing = fixtureLookup.findFixturePosting(employer.getId(), marker);
		if (existing.isPresent()) return response(workerId, existing.get(), false);

		// 이 함수에서 만든 새 공고·일정만 가공한다. 기존 지원/약정은 조회하거나 수정하지 않는다.
		var conditions = List.of(day(today.plusDays(7)), day(today.plusDays(8)), day(today.plusDays(9)),
			day(today.plusDays(10)), day(today.plusDays(11)), day(today.plusDays(12)),
			day(today.minusDays(2)), day(today.minusDays(1)), day(today.plusDays(13)));
		var request = new CreateJobPostingRequest(marker, "실제 모집·계약이 아닌 APP-04 상태별 조회 시연용 가상 데이터입니다.",
			"FC03030100030103", "0003", "APP-04 가상 작업지 (실제 근무 아님)", new BigDecimal("37.000000"),
			new BigDecimal("127.000000"), "GMT+9", 3, 120000L, conditions);
		var posting = postings.saveAndFlush(new JobPosting(employer, request));
		var dates = schedules.saveAllAndFlush(conditions.stream().map(date -> new WorkSchedule(posting, date)).toList());
		var firstApplied = nowUtc.minusDays(3);
		var confirmedAt = firstApplied.plusHours(1);

		add(workerId, employer.getId(), posting.getId(), marker, dates.get(0), "PENDING", 1, true, firstApplied);
		add(workerId, employer.getId(), posting.getId(), marker, dates.get(1), "PENDING", 1, true, firstApplied.plusMinutes(1));
		add(workerId, employer.getId(), posting.getId(), marker, dates.get(2), "NOT_SELECTED", 1, true, firstApplied.plusMinutes(2));
		add(workerId, employer.getId(), posting.getId(), marker, dates.get(3), "PENDING", 1, true, firstApplied.plusMinutes(3));
		dates.get(3).cancelRecruitment(nowUtc);

		long confirmed = add(workerId, employer.getId(), posting.getId(), marker, dates.get(4), "SELECTED", 1, true, firstApplied.plusMinutes(4));
		assignment(workerId, employer.getId(), posting.getId(), marker, confirmed, confirmedAt);
		long cancelled = add(workerId, employer.getId(), posting.getId(), marker, dates.get(5), "SELECTED", 1, true, firstApplied.plusMinutes(5));
		long cancelledAssignment = assignment(workerId, employer.getId(), posting.getId(), marker, cancelled, confirmedAt);
		// 현재 변경과 보험용 불변 버전을 기존 저장 경계로 함께 남긴다.
		changes.recordValidatedChange(employer.getId(), cancelledAssignment, WorkChange.Type.CANCELLED, null,
			WorkChange.Reason.FARM_CIRCUMSTANCES, null, "[APP-04 시연] 가상 농장 사정 취소");
		long early = add(workerId, employer.getId(), posting.getId(), marker, dates.get(6), "SELECTED", 1, true, firstApplied.plusMinutes(6));
		long earlyAssignment = assignment(workerId, employer.getId(), posting.getId(), marker, early, confirmedAt);
		changes.recordValidatedChange(employer.getId(), earlyAssignment, WorkChange.Type.EARLY_ENDED,
			conditions.get(6).workDate().atTime(14, 0).atOffset(ZoneOffset.ofHours(9)),
			WorkChange.Reason.WEATHER, WorkChange.WeatherReason.RAIN, "[APP-04 시연] 가상 강수 조기 종료");
		long completed = add(workerId, employer.getId(), posting.getId(), marker, dates.get(7), "SELECTED", 1, true, firstApplied.plusMinutes(7));
		long completedAssignment = assignment(workerId, employer.getId(), posting.getId(), marker, completed, confirmedAt);
		completion.complete(employer.getId(), completedAssignment);

		add(workerId, employer.getId(), posting.getId(), marker, dates.get(8), "NOT_SELECTED", 1, false, firstApplied.plusMinutes(8));
		add(workerId, employer.getId(), posting.getId(), marker, dates.get(8), "PENDING", 2, true, firstApplied.plusMinutes(9));

		// 지원 당시 v1을 보존한 뒤, 새로 만든 둘째 일정만 v2로 변경한다.
		var changed = dates.get(1);
		revisions.save(WorkScheduleRevision.baseline(changed, nowUtc));
		var changedDay = changed.getWorkDate();
		changed.updateConditions(new CreateJobPostingRequest.Schedule(changedDay, changedDay.atTime(10, 0),
			changedDay.atTime(18, 0), changedDay.atTime(12, 0), changedDay.atTime(13, 0)));
		revisions.save(WorkScheduleRevision.updated(changed, employer.getId(), nowUtc));
		schedules.saveAllAndFlush(dates);
		return response(workerId, posting.getId(), true);
	}

	private long add(long workerId, long employerId, long postingId, String marker, WorkSchedule schedule,
			String status, int round, boolean current, LocalDateTime appliedAt) {
		if (fixtures.insertApplication(workerId, schedule.getId(), postingId, employerId, marker,
			status, round, current ? 1 : 0, appliedAt) != 1) throw ApplicationException.unavailable();
		return fixtures.findApplicationId(workerId, schedule.getId(), round).orElseThrow(ApplicationException::unavailable);
	}
	private long assignment(long workerId, long employerId, long postingId, String marker, long applicationId, LocalDateTime confirmedAt) {
		if (fixtures.insertAssignment(applicationId, workerId, postingId, employerId, marker, confirmedAt) != 1) {
			throw ApplicationException.unavailable();
		}
		return fixtures.findAssignmentId(applicationId).orElseThrow(ApplicationException::unavailable);
	}
	private FixtureResponse response(long workerId, long postingId, boolean created) {
		var cases = new ArrayList<Scenario>();
		for (var row : fixtures.findScenarios(workerId, postingId)) {
			boolean current = row.getCurrentFlag() == 1;
			boolean changed = row.getAppliedTermsVersion() != null && row.getAppliedTermsVersion() != row.getLatestTermsVersion();
			String label = !current ? "재지원의 과거 회차" : row.getApplicationRound() > 1 ? "재지원의 현재 회차"
				: changed ? "지원 후 조건 변경" : switch (row.getDisplayStatus()) {
					case "PENDING" -> "지원 대기";
					case "NOT_SELECTED" -> "미선정";
					case "CONFIRMED" -> "근무 확정";
					case "CANCELLED" -> row.getAssignmentId() == null ? "모집 취소" : "확정 후 취소";
					case "EARLY_ENDED" -> "조기 종료";
					case "COMPLETED" -> "근무 완료";
					default -> "상태 확인 필요";
				};
			cases.add(new Scenario(label, row.getApplicationId(), row.getWorkScheduleId(), row.getWorkDate(),
				row.getApplicationRound(), current, row.getAssignmentId(), row.getDisplayStatus(), changed));
		}
		return new FixtureResponse(postingId, created, List.copyOf(cases));
	}
	private static CreateJobPostingRequest.Schedule day(LocalDate day) {
		return new CreateJobPostingRequest.Schedule(day, day.atTime(9, 0), day.atTime(17, 0), day.atTime(12, 0), day.atTime(13, 0));
	}
	public record FixtureResponse(long jobPostingId, boolean created, List<Scenario> scenarios) {}
	public record Scenario(String label, long applicationId, long workScheduleId, LocalDate workDate,
		int applicationRound, boolean current, Long assignmentId, String displayStatus, boolean conditionsChanged) {}
}

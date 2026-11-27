package com.wonday.farm.demo.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
public class DemoJobApplicationService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final DemoJobApplicationRepository repository;
	private final ApplicationRequestLockRepository requestLocks;
	private final Clock clock;

	public DemoJobApplicationService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, DemoJobApplicationRepository repository,
			ApplicationRequestLockRepository requestLocks, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
		this.repository = repository;
		this.requestLocks = requestLocks;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public HistoryResponse history(long workerId, long postingId) {
		if (postingId <= 0) throw ApplicationException.invalidRequest();
		requireWorker(workerId);
		if (!postings.existsById(postingId)) {
			throw new ApplicationException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다.");
		}
		return new HistoryResponse(repository.findOwnHistory(workerId, postingId).stream().map(row ->
			new ApplicationHistory(row.getApplicationId(), row.getWorkScheduleId(), row.getApplicationRound(),
				row.getStatus(), row.getCurrentFlag() == 1, row.getAppliedTermsVersion(),
				row.getAssignmentId(), row.getChangeType())).toList());
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public FixtureResponse prepareFixture(long workerId) {
		requireWorker(workerId);
		// 실제 지원과 같은 근로자 잠금을 사용해 준비 버튼 연타·동시 지원과 겹치지 않게 한다.
		requestLocks.ensureExists(workerId);
		requestLocks.lockWorker(workerId).orElseThrow(ApplicationException::unavailable);
		var employer = members.findByLoginId("demo-employer")
			.filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> ApplicationException.conflict("DEMO_EMPLOYER_UNAVAILABLE",
				"demo-employer 고용주 계정을 먼저 확인해 주세요."));
		var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		var today = LocalDate.ofInstant(now, ZoneOffset.ofHours(9));
		String marker = "[APP-02 시연·근로자 " + workerId + "·" + today + "]";
		var existing = repository.findFixturePosting(employer.getId(), marker);
		if (existing.isPresent()) return new FixtureResponse(existing.get(), false);

		var dates = List.of(conditions(today.plusDays(7)), conditions(today.plusDays(8)), conditions(today.plusDays(9)));
		var request = new CreateJobPostingRequest(marker,
			"실제 모집이 아닌 지원 API 시연용 공고입니다. 첫째·둘째 날짜는 신규 지원, 셋째 날짜는 미선정 후 재지원용입니다.",
			"FC03030100030103", "0003", "APP-02 가상 작업지 (실제 근무 아님)",
			new BigDecimal("37.000000"), new BigDecimal("127.000000"), "GMT+9", 3, 120000L, dates);
		var posting = postings.saveAndFlush(new JobPosting(employer, request));
		var created = schedules.saveAllAndFlush(dates.stream().map(date -> new WorkSchedule(posting, date)).toList());
		// 새로 생성한 셋째 일정에만 가상 미선정 1회차를 준비한다. 기존 공고·지원은 변경하지 않는다.
		int inserted = repository.insertNotSelectedFixture(workerId, created.get(2).getId(),
			LocalDateTime.ofInstant(now.minusSeconds(3600), ZoneOffset.UTC));
		if (inserted != 1) throw ApplicationException.unavailable();
		return new FixtureResponse(posting.getId(), true);
	}

	private void requireWorker(long workerId) {
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));
	}

	private static CreateJobPostingRequest.Schedule conditions(LocalDate day) {
		return new CreateJobPostingRequest.Schedule(day, day.atTime(9, 0), day.atTime(17, 0),
			day.atTime(12, 0), day.atTime(13, 0));
	}

	public record FixtureResponse(long jobPostingId, boolean created) {}
	public record HistoryResponse(List<ApplicationHistory> applications) {}
	public record ApplicationHistory(long applicationId, long workScheduleId, int applicationRound,
		String status, boolean current, Integer appliedTermsVersion, Long assignmentId, String changeType) {}
}
package com.wonday.farm.application.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.dto.ApplyJobPostingResponse;
import com.wonday.farm.application.entity.ApplicationReceipt;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationReceiptRepository;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.application.validation.ApplicationRequestValidator;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobApplicationService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final JobApplicationRepository applications;
	private final ApplicationRequestLockRepository requestLocks;
	private final ApplicationReceiptRepository receipts;
	private final ApplicationReceiptCodec codec;
	private final Clock clock;

	public JobApplicationService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, JobApplicationRepository applications,
			ApplicationRequestLockRepository requestLocks, ApplicationReceiptRepository receipts,
			ApplicationReceiptCodec codec, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
		this.applications = applications;
		this.requestLocks = requestLocks;
		this.receipts = receipts;
		this.codec = codec;
		this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public Result apply(long workerId, long postingId, String requestKey, ApplyJobPostingRequest request) {
		String key = ApplicationRequestValidator.requestKey(requestKey);
		var selected = ApplicationRequestValidator.schedules(postingId, request);
		String fingerprint = ApplicationRequestValidator.fingerprint(postingId, selected);
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));

		// 근로자별 요청 잠금 → 공고 → 일정 ID 오름차순 → 현재 지원 순서다.
		// 같은 키의 첫 요청이 진행 중이면 커밋/롤백을 기다린 뒤 성공 기록을 다시 읽는다.
		requestLocks.ensureExists(workerId);
		requestLocks.lockWorker(workerId).orElseThrow(ApplicationException::unavailable);
		var previousReceipt = receipts.findByWorkerIdAndRequestKey(workerId, key);
		if (previousReceipt.isPresent()) {
			var receipt = previousReceipt.get();
			if (receipt.getJobPostingId() != postingId || !fingerprint.equals(receipt.getRequestFingerprint())) {
				throw conflict("IDEMPOTENCY_KEY_CONFLICT", "이미 접수한 요청 키에 다른 내용을 보낼 수 없습니다. 새 요청 키를 사용해 주세요.");
			}
			var body = codec.decode(receipt.getResponsePayload());
			if (body == null || body.jobPostingId() != postingId) throw new IllegalStateException("Invalid stored receipt");
			// 재요청은 현재 모집 상태·조건·시각을 다시 적용하지 않고 최초 성공 결과를 반환한다.
			return new Result(true, body);
		}

		var posting = postings.findByIdForUpdate(postingId).orElseThrow(() ->
			new ApplicationException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다."));
		var plans = new ArrayList<Plan>();
		var zone = storedZone(posting.getTimeZone());
		for (var item : selected) {
			var schedule = schedules.findByIdAndJobPosting_Id(item.workScheduleId(), postingId).orElseThrow(() ->
				new ApplicationException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "해당 공고의 일정을 찾을 수 없습니다."));
			if (schedule.getTermsVersion() != item.termsVersion()) {
				throw conflict("SCHEDULE_TERMS_CHANGED", "근무 조건이 변경되었습니다. 최신 조건을 확인한 뒤 다시 지원해 주세요.");
			}
			requireOpen(schedule);
			Instant start = plannedStart(schedule, zone);
			if (schedules.countOccupyingAssignments(schedule.getId()) >= posting.getHeadcount()) {
				throw conflict("CAPACITY_FULL", "확정 인원이 정원을 채워 해당 날짜에 지원할 수 없습니다.");
			}
			if (applications.findBlockingAssignment(workerId, schedule.getId()).isPresent()) {
				throw conflict("ALREADY_CONFIRMED", "해당 날짜에 유효한 확정 근무가 있습니다.");
			}
			var current = applications.findCurrentForUpdate(workerId, schedule.getId()).orElse(null);
			validateReapplication(current);
			int lastRound = applications.maxRound(workerId, schedule.getId());
			if (lastRound < 0) throw ApplicationException.unavailable();
			if (lastRound == Integer.MAX_VALUE) {
				throw conflict("APPLICATION_ROUND_EXHAUSTED", "지원 회차를 더 증가시킬 수 없습니다.");
			}
			plans.add(new Plan(schedule, current, lastRound + 1, start));
		}

		// 모든 잠금과 조회가 끝난 뒤의 같은 서버 시각으로 전체 시작 경계를 검사한다.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		for (var plan : plans) {
			if (!now.isBefore(plan.start())) {
				throw conflict("SCHEDULE_STARTED", "근무 시작 시각이 되어 해당 날짜에 지원할 수 없습니다.");
			}
		}
		LocalDateTime nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		var retired = new ArrayList<JobApplication>();
		for (var plan : plans) {
			if (plan.previous() != null) {
				plan.previous().retireForReapplication();
				retired.add(plan.previous());
			}
		}
		// 현재 회차 UNIQUE 자리를 먼저 비운다. 이후 실패하면 이 변경까지 같은 트랜잭션에서 롤백된다.
		if (!retired.isEmpty()) applications.saveAllAndFlush(retired);
		var created = plans.stream().map(plan -> new JobApplication(workerId, plan.schedule().getId(),
			plan.round(), plan.schedule().getTermsVersion(), nowUtc)).toList();
		var saved = applications.saveAllAndFlush(created);
		var body = new ApplyJobPostingResponse(postingId, saved.stream().map(app ->
			new ApplyJobPostingResponse.Application(app.getId(), app.getWorkScheduleId(), app.getApplicationRound(),
				app.getStatus(), app.getAppliedTermsVersion(), now.atZone(zone).toOffsetDateTime())).toList());

		// 지원 행과 성공 기록은 한 번에 커밋한다. 실패 기록·만료 시각·자동 삭제는 추가하지 않는다.
		receipts.saveAndFlush(new ApplicationReceipt(workerId, key, postingId, fingerprint, codec.encode(body), nowUtc));
		return new Result(false, body);
	}

	private void validateReapplication(JobApplication current) {
		if (current == null) return;
		if (applications.findCancelledAssignment(current.getId()).isPresent()) return;
		if ("PENDING".equals(current.getStatus())) {
			throw conflict("ALREADY_APPLIED", "이미 지원 대기 중인 날짜가 포함되어 있습니다.");
		}
		if ("SELECTED".equals(current.getStatus())) {
			throw conflict("ALREADY_CONFIRMED", "이미 선정된 날짜가 포함되어 있습니다.");
		}
		if (!"NOT_SELECTED".equals(current.getStatus())) {
			throw conflict("APPLICATION_STATUS_UNAVAILABLE", "기존 지원 상태를 확인한 뒤 다시 지원해 주세요.");
		}
	}

	private static void requireOpen(WorkSchedule schedule) {
		if (schedule.getStatus() == WorkSchedule.Status.OPEN) return;
		if (schedule.getStatus() == WorkSchedule.Status.PAUSED) throw conflict("RECRUITMENT_PAUSED", "일시중단된 날짜입니다.");
		if (schedule.getStatus() == WorkSchedule.Status.CLOSED) throw conflict("RECRUITMENT_CLOSED", "모집이 마감된 날짜입니다.");
		if (schedule.getStatus() == WorkSchedule.Status.REMOVED) throw conflict("RECRUITMENT_CANCELLED", "모집이 취소된 날짜입니다.");
		throw conflict("SCHEDULE_STATUS_UNAVAILABLE", "일정의 모집 상태를 확인할 수 없습니다.");
	}

	private static ZoneId storedZone(String value) {
		if (value != null && !value.isBlank()) {
			try { return ZoneId.of(value); } catch (DateTimeException ignored) { }
		}
		throw conflict("TIME_ZONE_UNAVAILABLE", "공고의 작업지 시간대를 확인해야 합니다.");
	}

	private static Instant plannedStart(WorkSchedule schedule, ZoneId zone) {
		var start = schedule.getPlannedStartAt();
		if (start == null || zone.getRules().getValidOffsets(start).size() != 1) {
			throw conflict("SCHEDULE_START_TIME_UNAVAILABLE", "일정의 예정 시작 시각을 하나로 해석할 수 없습니다.");
		}
		return start.toInstant(zone.getRules().getValidOffsets(start).getFirst());
	}

	private static ApplicationException conflict(String code, String message) {
		return ApplicationException.conflict(code, message);
	}

	private record Plan(WorkSchedule schedule, JobApplication previous, int round, Instant start) {}
	public record Result(boolean replayed, ApplyJobPostingResponse body) {}
}
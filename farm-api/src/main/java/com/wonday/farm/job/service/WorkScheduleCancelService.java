package com.wonday.farm.job.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;

import com.wonday.farm.job.dto.CancelWorkScheduleRequest;
import com.wonday.farm.job.dto.CancelledWorkScheduleResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkScheduleCancelException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class WorkScheduleCancelService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final Clock clock;

	public WorkScheduleCancelService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
		this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CancelledWorkScheduleResponse cancel(long employerId, long postingId, long scheduleId,
			@Valid CancelWorkScheduleRequest request) {
		// 로그인 당시 권한뿐 아니라 현재 DB의 역할도 확인한다.
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		if (postingId <= 0 || scheduleId <= 0) {
			throw new WorkScheduleCancelException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"공고 ID와 일정 ID는 양수여야 합니다.", null);
		}

		// 일정 수정과 같은 공고 → 일정 잠금 순서다. 향후 지원·선정도 이 순서를 준수해야 한다.
		var posting = postings.findOwnedForUpdate(postingId, employerId).orElseThrow(() ->
			new WorkScheduleCancelException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다.", null));
		var schedule = schedules.findByIdAndJobPosting_Id(scheduleId, postingId).orElseThrow(() ->
			new WorkScheduleCancelException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "해당 공고의 일정을 찾을 수 없습니다.", null));

		// 성공 응답을 받지 못해 재요청해도 같은 결과를 반환한다. 시작 시각이 지나도 이미 된 취소를 되돌리지 않는다.
		if (schedule.getStatus() == WorkSchedule.Status.REMOVED) return response(schedule);
		if (!request.expectedTermsVersion().equals(schedule.getTermsVersion())) {
			throw conflict("SCHEDULE_VERSION_CONFLICT", "조건이 변경되었습니다. 최신 일정을 조회한 후 다시 취소해 주세요.", "expectedTermsVersion");
		}
		if (!EnumSet.of(WorkSchedule.Status.OPEN, WorkSchedule.Status.PAUSED, WorkSchedule.Status.CLOSED).contains(schedule.getStatus())) {
			throw conflict("SCHEDULE_NOT_CANCELLABLE", "모집 취소할 수 없는 일정 상태입니다.", null);
		}
		// 모든 확정 근무가 CANCELLED이면 0건이다. 조기 종료·취소되지 않은 근무는 남은 확정자로 계산한다.
		if (schedules.countOccupyingAssignments(scheduleId) > 0) {
			throw conflict("SCHEDULE_ALREADY_CONFIRMED", "확정 근무가 남아 있어 날짜 모집을 취소할 수 없습니다. 개인별 근무 취소가 필요합니다.", null);
		}

		Instant plannedStart = plannedStart(schedule, posting.getTimeZone());
		// 잠금 대기와 DB 조회가 끝난 뒤의 서버 시각으로 검사한다. 시작 시각과 같아도 이미 시작된 것으로 본다.
		Instant now = clock.instant();
		if (!now.isBefore(plannedStart)) {
			throw conflict("SCHEDULE_ALREADY_STARTED", "예정 시작 시각이 되어 날짜 모집을 취소할 수 없습니다.", null);
		}
		schedule.cancelRecruitment(LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
		// JPA 변경 감지로 상태와 취소 시각만 저장한다. 지원·약정·기존 변경 기록을 삭제하거나 새 개인 취소를 만들지 않는다.
		return response(schedule);
	}

	private static Instant plannedStart(WorkSchedule schedule, String timeZone) {
		if (timeZone == null || timeZone.isBlank()) {
			throw conflict("TIME_ZONE_UNAVAILABLE", "공고의 작업지 시간대를 먼저 확인해야 합니다.", null);
		}
		ZoneId zone;
		try {
			zone = ZoneId.of(timeZone);
		} catch (DateTimeException exception) {
			throw conflict("TIME_ZONE_UNAVAILABLE", "공고의 작업지 시간대를 먼저 확인해야 합니다.", null);
		}
		LocalDateTime start = schedule.getPlannedStartAt();
		if (start == null) throw conflict("SCHEDULE_START_TIME_UNAVAILABLE", "일정의 예정 시작 시각을 확인해야 합니다.", null);
		var offsets = zone.getRules().getValidOffsets(start);
		if (offsets.size() != 1) {
			// 기존 서머타임 지역의 모호하거나 존재하지 않는 시각을 임의로 보정하지 않는다.
			throw conflict("SCHEDULE_START_TIME_UNAVAILABLE", "작업지에서 예정 시작 시각을 하나로 해석할 수 없습니다.", null);
		}
		return start.toInstant(offsets.getFirst());
	}

	private static CancelledWorkScheduleResponse response(WorkSchedule schedule) {
		return new CancelledWorkScheduleResponse(schedule.getJobPosting().getId(), schedule.getId(), schedule.getWorkDate(),
			schedule.getStatus(), schedule.getTermsVersion(),
			schedule.getCancelledAt() == null ? null : schedule.getCancelledAt().atOffset(ZoneOffset.UTC));
	}

	private static WorkScheduleCancelException conflict(String code, String message, String field) {
		return new WorkScheduleCancelException(HttpStatus.CONFLICT, code, message, field);
	}
}

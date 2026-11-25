package com.wonday.farm.job.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.UpdateWorkScheduleRequest;
import com.wonday.farm.job.dto.UpdatedWorkScheduleResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.exception.WorkScheduleUpdateException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.job.validation.WorkScheduleValidator;
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
public class WorkScheduleUpdateService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final WorkScheduleRevisionRepository revisions;
	private final Clock clock;

	public WorkScheduleUpdateService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, WorkScheduleRevisionRepository revisions, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
		this.revisions = revisions;
		this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public UpdatedWorkScheduleResponse update(long employerId, long postingId, long scheduleId,
			@Valid UpdateWorkScheduleRequest request) {
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		if (postingId <= 0 || scheduleId <= 0) {
			throw new JobPostingException("INVALID_REQUEST", "공고 ID와 일정 ID는 양수여야 합니다.", null);
		}

		// 공고 → 일정 순서로 잠근다. READ_COMMITTED로 잠금 대기 전의 오래된 확정 집계를 읽지 않는다.
		var posting = postings.findOwnedForUpdate(postingId, employerId).orElseThrow(() ->
			new WorkScheduleUpdateException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다.", null));
		var schedule = schedules.findByIdAndJobPosting_Id(scheduleId, postingId).orElseThrow(() ->
			new WorkScheduleUpdateException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "해당 공고의 일정을 찾을 수 없습니다.", null));
		if (!request.expectedTermsVersion().equals(schedule.getTermsVersion())) {
			throw conflict("SCHEDULE_VERSION_CONFLICT", "조건이 변경되었습니다. 최신 일정을 조회한 후 다시 수정해 주세요.", "expectedTermsVersion");
		}
		if (!EnumSet.of(WorkSchedule.Status.OPEN, WorkSchedule.Status.PAUSED, WorkSchedule.Status.CLOSED).contains(schedule.getStatus())) {
			throw conflict("SCHEDULE_NOT_EDITABLE", "모집 취소된 일정은 수정할 수 없습니다.", null);
		}
		if (schedules.countOccupyingAssignments(scheduleId) > 0) {
			throw conflict("SCHEDULE_ALREADY_CONFIRMED", "확정 근무가 남아 있는 일정은 날짜·시간·휴게 구간을 수정할 수 없습니다.", null);
		}
		var conditions = new CreateJobPostingRequest.Schedule(request.workDate(), request.plannedStartAt(),
			request.plannedEndAt(), request.breakStartAt(), request.breakEndAt());
		WorkScheduleValidator.validate(conditions, storedZone(posting.getTimeZone()), "");
		if (schedules.existsByJobPosting_IdAndWorkDateAndIdNot(postingId, request.workDate(), scheduleId)) {
			throw new JobPostingException("DUPLICATE_WORK_DATE", "같은 공고의 다른 일정과 근무일이 중복됩니다.", "workDate");
		}
		// 같은 조건을 다시 저장할 때는 조건 버전과 이력을 불필요하게 늘리지 않는다.
		if (schedule.hasSameConditions(conditions)) return response(schedule);
		if (schedule.getTermsVersion() == Integer.MAX_VALUE) {
			throw conflict("SCHEDULE_VERSION_EXHAUSTED", "일정 조건 버전을 더 증가시킬 수 없습니다.", null);
		}
		var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
		if (!revisions.existsByWorkSchedule_IdAndTermsVersion(scheduleId, schedule.getTermsVersion())) {
			revisions.save(WorkScheduleRevision.baseline(schedule, now));
		}
		schedule.updateConditions(conditions);
		revisions.save(WorkScheduleRevision.updated(schedule, employerId, now));
		// 기존 지원·일정 ID, 모집 상태, 확정 당시 약정은 변경하지 않는다.
		return response(schedule);
	}

	private static ZoneId storedZone(String value) {
		if (value != null && !value.isBlank()) {
			try {
				return ZoneId.of(value);
			} catch (DateTimeException ignored) {
				// 기존 시간대가 잘못 저장된 경우도 임의로 GMT+9로 바꾸지 않는다.
			}
		}
		throw conflict("TIME_ZONE_UNAVAILABLE", "공고의 작업지 시간대를 먼저 확인해야 합니다.", null);
	}

	private static UpdatedWorkScheduleResponse response(WorkSchedule schedule) {
		return new UpdatedWorkScheduleResponse(schedule.getJobPosting().getId(), schedule.getId(), schedule.getWorkDate(),
			schedule.getPlannedStartAt(), schedule.getPlannedEndAt(), schedule.getBreakStartAt(), schedule.getBreakEndAt(),
			schedule.getStatus(), schedule.getTermsVersion());
	}

	private static WorkScheduleUpdateException conflict(String code, String message, String field) {
		return new WorkScheduleUpdateException(HttpStatus.CONFLICT, code, message, field);
	}
}

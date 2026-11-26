package com.wonday.farm.job.validation;

import java.time.LocalDateTime;
import java.time.ZoneId;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.exception.JobPostingException;

// 등록과 수정에서 같은 근무·휴게 규칙을 사용한다. 등록은 배열 경로, 수정은 단일 필드명을 반환한다.
public final class WorkScheduleValidator {
	private WorkScheduleValidator() {}

	public static void validate(CreateJobPostingRequest.Schedule schedule, ZoneId zone, String prefix) {
		validateWorkTime(schedule, zone, prefix);
		validateBreakTime(schedule, zone, prefix);
	}

	private static void validateWorkTime(CreateJobPostingRequest.Schedule schedule, ZoneId zone, String prefix) {
		validateLocalTime(schedule.plannedStartAt(), zone, field(prefix, "plannedStartAt"));
		validateLocalTime(schedule.plannedEndAt(), zone, field(prefix, "plannedEndAt"));
		if (!schedule.plannedStartAt().toLocalDate().equals(schedule.workDate())) {
			throw new JobPostingException("INVALID_WORK_TIME", "근무일은 예정 시작 일시의 날짜와 같아야 합니다.", field(prefix, "workDate"));
		}
		if (!schedule.plannedEndAt().isAfter(schedule.plannedStartAt())) {
			throw new JobPostingException("INVALID_WORK_TIME", "예정 종료 일시는 시작 일시 이후여야 합니다.", field(prefix, "plannedEndAt"));
		}
	}

	private static void validateBreakTime(CreateJobPostingRequest.Schedule schedule, ZoneId zone, String prefix) {
		var start = schedule.breakStartAt();
		var end = schedule.breakEndAt();
		if (start == null && end == null) return;
		if (start == null) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 시작을 입력해 주세요.", field(prefix, "breakStartAt"));
		}
		if (end == null) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료를 입력해 주세요.", field(prefix, "breakEndAt"));
		}
		validateLocalTime(start, zone, field(prefix, "breakStartAt"));
		validateLocalTime(end, zone, field(prefix, "breakEndAt"));
		if (!end.isAfter(start)) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료는 휴게 시작 이후여야 합니다.", field(prefix, "breakEndAt"));
		}
		if (start.isBefore(schedule.plannedStartAt())) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 시작은 근무 시작 이전일 수 없습니다.", field(prefix, "breakStartAt"));
		}
		if (end.isAfter(schedule.plannedEndAt())) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료는 근무 종료를 넘을 수 없습니다.", field(prefix, "breakEndAt"));
		}
	}

	private static void validateLocalTime(LocalDateTime time, ZoneId zone, String field) {
		if (time.getYear() < 1000 || time.getYear() > 9999 || time.getNano() % 1000 != 0
				|| zone.getRules().getValidOffsets(time).size() != 1) {
			throw new JobPostingException("INVALID_LOCAL_TIME", "작업지에서 해석 가능한 일시를 마이크로초 정밀도 이내로 입력해 주세요.", field);
		}
	}

	private static String field(String prefix, String name) {
		return prefix.isEmpty() ? name : prefix + "." + name;
	}
}

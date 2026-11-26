package com.wonday.farm.job.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.job.entity.WorkSchedule;

// 엔티티를 직접 노출하지 않고 고용주 본인의 공고와 일정별 모집 현황만 반환한다.
public record MyJobPostingsResponse(List<Posting> jobPostings) {
	public record Posting(
		long jobPostingId,
		String title,
		String description,
		String cropCode,
		String workTypeCode,
		String workLocation,
		String timeZone,
		int headcount,
		long dailyWage,
		List<Schedule> schedules
	) {}

	public record Schedule(
		long workScheduleId,
		LocalDate workDate,
		LocalDateTime plannedStartAt,
		LocalDateTime plannedEndAt,
		LocalDateTime breakStartAt,
		LocalDateTime breakEndAt,
		WorkSchedule.Status status,
		int termsVersion,
		long applicantCount,
		long confirmedCount,
		long remainingSlots
	) {}
}

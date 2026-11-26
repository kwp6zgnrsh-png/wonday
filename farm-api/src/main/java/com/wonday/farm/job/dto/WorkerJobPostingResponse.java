package com.wonday.farm.job.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.job.entity.WorkSchedule;

// 상세에서는 중단·마감·취소·지난 날짜도 보존한 상태로 표시한다. 개인별 약정 조회 응답과는 별개다.
public record WorkerJobPostingResponse(long jobPostingId, String title, String description, String workLocation,
	String cropCode, String cropName, String workTypeCode, String workTypeName, String timeZone,
	int headcount, long dailyWage, List<Schedule> schedules, BigDecimal latitude, BigDecimal longitude) {
	public record Schedule(long workScheduleId, LocalDate workDate, LocalDateTime plannedStartAt,
		LocalDateTime plannedEndAt, LocalDateTime breakStartAt, LocalDateTime breakEndAt,
		WorkSchedule.Status status, int termsVersion, long confirmedCount, long remainingSlots,
		boolean canApply, String unavailableReason) {}
}

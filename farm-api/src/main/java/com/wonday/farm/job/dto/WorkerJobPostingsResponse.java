package com.wonday.farm.job.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// 목록은 시작 전 OPEN 날짜만 요약한다. 정원이 찼어도 공고를 숨기지 않는다.
public record WorkerJobPostingsResponse(List<Posting> jobPostings, int page, int size, boolean hasNext) {
	public record Posting(long jobPostingId, String title, String workLocation,
		String cropCode, String cropName, String workTypeCode, String workTypeName,
		int headcount, long dailyWage, List<LocalDate> recruitingWorkDates,
		List<RecruitingSchedule> recruitingSchedules, BigDecimal latitude, BigDecimal longitude) {}

	// 목록에서도 날짜별 정원 충족·본인 지원 여부를 구분할 수 있게 제공한다.
	public record RecruitingSchedule(long workScheduleId, LocalDate workDate, long confirmedCount,
		long remainingSlots, boolean canApply, String unavailableReason) {}
}

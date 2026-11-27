package com.wonday.farm.application.dto;

import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;

// 재요청에는 현재 선정 상태가 아니라 이 최초 접수 결과를 반환한다.
public record ApplyJobPostingResponse(long jobPostingId, List<Application> applications) {
	public ApplyJobPostingResponse { applications = List.copyOf(applications); }

	public record Application(long applicationId, long workScheduleId, int applicationRound,
		String status, int appliedTermsVersion,
		@JsonFormat(shape = JsonFormat.Shape.STRING,
			without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE) OffsetDateTime appliedAt) {}
}
package com.wonday.farm.job.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.wonday.farm.job.entity.WorkSchedule;

// cancelledAt은 오프셋이 명시된 UTC 시각이다. 기존 REMOVED 행에 시각이 없으면 null을 유지한다.
public record CancelledWorkScheduleResponse(long jobPostingId, long workScheduleId, LocalDate workDate,
	WorkSchedule.Status status, int termsVersion, OffsetDateTime cancelledAt) {}

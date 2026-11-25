package com.wonday.farm.job.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.wonday.farm.job.entity.WorkSchedule;

public record UpdatedWorkScheduleResponse(long jobPostingId, long workScheduleId, LocalDate workDate,
	LocalDateTime plannedStartAt, LocalDateTime plannedEndAt, LocalDateTime breakStartAt,
	LocalDateTime breakEndAt, WorkSchedule.Status status, int termsVersion) {}

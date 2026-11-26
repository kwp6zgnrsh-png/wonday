package com.wonday.farm.job.dto;

import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.job.entity.WorkSchedule;

public record CreateJobPostingResponse(long jobPostingId, long employerId, int headcount,
	List<Schedule> schedules) {
	public record Schedule(long workScheduleId, LocalDate workDate, WorkSchedule.Status status) {}
}

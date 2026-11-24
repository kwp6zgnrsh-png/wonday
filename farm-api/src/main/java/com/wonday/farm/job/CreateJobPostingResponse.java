package com.wonday.farm.job;

import java.time.LocalDate;
import java.util.List;

public record CreateJobPostingResponse(long jobPostingId, long employerId, int headcount,
	List<Schedule> schedules) {
	public record Schedule(long workScheduleId, LocalDate workDate, WorkSchedule.Status status) {}
}

package com.wonday.farm.job.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.job.dto.CreateJobPostingRequest;

// API와 서비스 단위 테스트가 함께 사용하는 공고 등록 요청 생성 도구다.
public final class JobPostingFixtures {
	private JobPostingFixtures() {}

	public static CreateJobPostingRequest request(String timeZone, CreateJobPostingRequest.Schedule... schedules) {
		return request(timeZone, "사과 수확과 운반 작업입니다.", schedules);
	}

	public static CreateJobPostingRequest request(String timeZone, String description, CreateJobPostingRequest.Schedule... schedules) {
		return new CreateJobPostingRequest("사과 수확 근로자 모집", description, JobPostingRequests.CROP_CODE,
			JobPostingRequests.WORK_TYPE_CODE, "충청북도 충주시 예시농장",
			new BigDecimal("37.123456"), new BigDecimal("127.123456"), timeZone, 2, 120000L, List.of(schedules));
	}

	public static CreateJobPostingRequest one(String date, String start, String end, String breakStart, String breakEnd) {
		return request("GMT+9", schedule(date, start, end, breakStart, breakEnd));
	}

	public static CreateJobPostingRequest.Schedule defaultSchedule() {
		return schedule("2026-11-30", "2026-11-30T08:00", "2026-11-30T16:00", null, null);
	}

	public static CreateJobPostingRequest.Schedule schedule(String date, String start, String end, String breakStart, String breakEnd) {
		return new CreateJobPostingRequest.Schedule(LocalDate.parse(date), LocalDateTime.parse(start),
			LocalDateTime.parse(end), breakStart == null ? null : LocalDateTime.parse(breakStart),
			breakEnd == null ? null : LocalDateTime.parse(breakEnd));
	}
}

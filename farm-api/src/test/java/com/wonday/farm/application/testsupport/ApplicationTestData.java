package com.wonday.farm.application.testsupport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import org.springframework.test.util.ReflectionTestUtils;

// APP-02 단위 테스트용 가상 데이터. 실제 회원·공고·개발 DB 값이 아니다.
// 기본 일정: GMT+9 2026-12-10 09:00~17:00 (시작 = UTC 2026-12-10T00:00:00Z).
public final class ApplicationTestData {
	public static final long WORKER_ID = 5L;
	public static final long POSTING_ID = 101L;
	public static final String KEY = "3f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f";

	private ApplicationTestData() {}

	public static Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	public static Member memberWithRoleCode(long id, String roleCode) {
		var member = member(id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "role", roleCode);
		return member;
	}

	public static JobPosting posting(long id, int headcount) {
		var request = new CreateJobPostingRequest("[가상] 사과 수확", "가상 공고", "CROP", "WORK", "가상 작업지",
			new BigDecimal("37.000000"), new BigDecimal("127.000000"), "GMT+9", headcount, 120000L, List.of());
		var posting = new JobPosting(member(10L, MemberRole.EMPLOYER), request);
		ReflectionTestUtils.setField(posting, "id", id);
		return posting;
	}

	public static WorkSchedule schedule(JobPosting posting, long id, LocalDate day, int termsVersion) {
		var schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(day, day.atTime(9, 0), day.atTime(17, 0),
			day.atTime(12, 0), day.atTime(13, 0)));
		ReflectionTestUtils.setField(schedule, "id", id);
		ReflectionTestUtils.setField(schedule, "termsVersion", termsVersion);
		return schedule;
	}

	public static JobApplication existing(long id, long scheduleId, int round, String status, LocalDateTime appliedAt) {
		var app = new JobApplication(WORKER_ID, scheduleId, round, 1, appliedAt);
		ReflectionTestUtils.setField(app, "id", id);
		ReflectionTestUtils.setField(app, "status", status);
		return app;
	}

	public static ApplyJobPostingRequest request(long... idAndVersionPairs) {
		var list = new java.util.ArrayList<ApplyJobPostingRequest.Schedule>();
		for (int i = 0; i < idAndVersionPairs.length; i += 2) {
			list.add(new ApplyJobPostingRequest.Schedule(idAndVersionPairs[i], (int) idAndVersionPairs[i + 1]));
		}
		return new ApplyJobPostingRequest(list);
	}
}

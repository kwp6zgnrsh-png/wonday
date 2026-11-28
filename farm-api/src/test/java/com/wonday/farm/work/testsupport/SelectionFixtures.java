package com.wonday.farm.work.testsupport;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import org.springframework.test.util.ReflectionTestUtils;

// WORK-02·03·04 단위 테스트용 가상 데이터. 실제 회원·공고·개발 DB 값이 아니다.
// 기본 일정: GMT+9 2026-12-10 09:00~17:00, 휴게 12:00~13:00, 시작 = UTC 2026-12-10T00:00:00Z.
// 고정 서버 시각: 2026-11-28T06:00:00.123456789Z (마이크로초 절삭 → .123456).
public final class SelectionFixtures {
	public static final long EMPLOYER = 10L;
	public static final long WORKER = 123L;
	public static final long POSTING = 101L;
	public static final long SCHEDULE = 201L;
	public static final long APPLICATION = 501L;
	public static final Instant NOW = Instant.parse("2026-11-28T06:00:00.123456789Z");
	public static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-28T06:00:00.123456");
	public static final LocalDate DAY = LocalDate.parse("2026-12-10");
	public static final Instant START = Instant.parse("2026-12-10T00:00:00Z");

	private SelectionFixtures() {}

	public static Clock clock(Instant instant) { return Clock.fixed(instant, ZoneOffset.UTC); }

	public static Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash-" + id, "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	public static Member memberWithRole(long id, String roleCode) {
		var member = member(id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "role", roleCode);
		return member;
	}

	public static JobPosting posting(int headcount) {
		var request = new CreateJobPostingRequest("[가상] 콩 경운", "가상 작업 내용", "FC03030100030103", "0003", "가상 작업지 1",
			new BigDecimal("37.123456"), new BigDecimal("127.654321"), "GMT+9", headcount, 120000L, List.of());
		var posting = new JobPosting(member(EMPLOYER, MemberRole.EMPLOYER), request);
		ReflectionTestUtils.setField(posting, "id", POSTING);
		return posting;
	}

	public static WorkSchedule schedule(JobPosting posting, WorkSchedule.Status status, int version) {
		var schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(DAY, DAY.atTime(9, 0), DAY.atTime(17, 0),
			DAY.atTime(12, 0), DAY.atTime(13, 0)));
		ReflectionTestUtils.setField(schedule, "id", SCHEDULE);
		ReflectionTestUtils.setField(schedule, "status", status);
		ReflectionTestUtils.setField(schedule, "termsVersion", version);
		return schedule;
	}

	public static JobApplication application(int appliedVersion, String status, boolean current) {
		var app = new JobApplication(WORKER, SCHEDULE, 2, appliedVersion, LocalDateTime.parse("2026-11-20T01:02:03.456789"));
		ReflectionTestUtils.setField(app, "id", APPLICATION);
		ReflectionTestUtils.setField(app, "status", status);
		ReflectionTestUtils.setField(app, "current", current);
		return app;
	}

	public static WorkAssignment assignment(long id, WorkSchedule schedule, LocalDateTime confirmedUtc) {
		var assignment = new WorkAssignment(APPLICATION, schedule, confirmedUtc);
		ReflectionTestUtils.setField(assignment, "id", id);
		return assignment;
	}
}

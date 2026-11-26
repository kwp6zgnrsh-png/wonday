package com.wonday.farm.job.entity;

import static com.wonday.farm.job.testsupport.JobPostingFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.job.testsupport.JobPostingFixtures;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

// WorkSchedule.cancelRecruitment 도메인 메서드. 서비스 검사를 통과한 뒤 호출되는 상태 전환만 본다.
class WorkScheduleCancelRecruitmentTest {
	private static final LocalDateTime FIRST = LocalDateTime.parse("2026-11-25T08:00:00.123456");

	@ParameterizedTest(name = "from {0}")
	@EnumSource(value = WorkSchedule.Status.class, names = {"OPEN", "PAUSED", "CLOSED"})
	void setsRemovedAndRecordsTheGivenTime(WorkSchedule.Status from) {
		var schedule = newSchedule();
		ReflectionTestUtils.setField(schedule, "status", from);

		schedule.cancelRecruitment(FIRST);

		assertThat(schedule.getStatus()).isEqualTo(WorkSchedule.Status.REMOVED);
		assertThat(schedule.getCancelledAt()).isEqualTo(FIRST);
	}

	@Test
	void secondCallDoesNotOverwriteTheFirstCancelTime() {
		var schedule = newSchedule();
		schedule.cancelRecruitment(FIRST);

		schedule.cancelRecruitment(FIRST.plusDays(1));

		assertThat(schedule.getCancelledAt()).isEqualTo(FIRST);
	}

	@Test
	void alreadyRemovedRowWithoutTimeStaysWithoutTime() {
		var schedule = newSchedule();
		ReflectionTestUtils.setField(schedule, "status", WorkSchedule.Status.REMOVED);

		schedule.cancelRecruitment(FIRST);

		assertThat(schedule.getCancelledAt()).isNull();
	}

	@Test
	void keepsConditionsVersionAndPostingLink() {
		var schedule = newSchedule();
		var posting = schedule.getJobPosting();
		var before = List.of(schedule.getWorkDate(), schedule.getPlannedStartAt(), schedule.getPlannedEndAt(),
			schedule.getBreakStartAt(), schedule.getBreakEndAt(), schedule.getTermsVersion());

		schedule.cancelRecruitment(FIRST);

		assertThat(List.of(schedule.getWorkDate(), schedule.getPlannedStartAt(), schedule.getPlannedEndAt(),
			schedule.getBreakStartAt(), schedule.getBreakEndAt(), schedule.getTermsVersion())).isEqualTo(before);
		assertThat(schedule.getJobPosting()).isSameAs(posting);
	}

	@Test
	void newScheduleHasNoCancelTime() {
		assertThat(newSchedule().getCancelledAt()).isNull();
	}

	private static WorkSchedule newSchedule() {
		var conditions = schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00", "2026-12-02T13:00");
		var posting = new JobPosting(new Member("employer", "hash", "고용주", MemberRole.EMPLOYER),
			JobPostingFixtures.request("GMT+9", conditions));
		return new WorkSchedule(posting, conditions);
	}
}

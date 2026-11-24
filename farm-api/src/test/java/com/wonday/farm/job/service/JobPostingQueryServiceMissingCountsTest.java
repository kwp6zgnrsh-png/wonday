package com.wonday.farm.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.MyJobPostingsResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 집계 누락 시 지원자 수와 확정 인원을 0으로 처리하는 정책을 검증하는 일반 단위 회귀 테스트.
// 별도 제외 태그 없이 unitTest의 기본 실행 대상에 포함한다.
@DisplayName("집계가 누락된 일정은 지원자 수와 확정 인원을 0으로 반환한다")
class JobPostingQueryServiceMissingCountsTest {
	private static final long EMPLOYER_ID = 10L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobPostingQueryService service = new JobPostingQueryService(members, postings, schedules);

	@Test
	void scheduleMissingFromCountsIsShownWithZeroApplicantsAndZeroConfirmed() {
		// 일정 두 개 중 하나의 집계만 반환하여 집계가 누락된 상황을 재현한다.
		Member employer = new Member("employer", "hash", "고용주", MemberRole.EMPLOYER);
		ReflectionTestUtils.setField(employer, "id", EMPLOYER_ID);
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(employer));

		JobPosting posting = new JobPosting(employer, new CreateJobPostingRequest("공고", "설명", "C", "W", "작업지",
			"GMT+9", 3, 120000L, List.of()));
		ReflectionTestUtils.setField(posting, "id", 1L);
		WorkSchedule counted = schedule(11L, posting, "2026-12-01");
		WorkSchedule notCounted = schedule(12L, posting, "2026-12-02");
		given(postings.findByEmployer_IdOrderByIdDesc(EMPLOYER_ID)).willReturn(List.of(posting));
		given(schedules.findOwnedSchedules(EMPLOYER_ID)).willReturn(List.of(counted, notCounted));
		given(schedules.countOwnedSchedules(EMPLOYER_ID)).willReturn(
			List.of(new JobPostingQueryServiceTest.Counts(11L, 4L, 1L)));

		var result = service.findMine(EMPLOYER_ID).jobPostings().getFirst().schedules();

		assertThat(result).extracting(MyJobPostingsResponse.Schedule::workScheduleId).containsExactly(11L, 12L);
		var missing = result.get(1);
		assertThat(missing.applicantCount()).isZero();
		assertThat(missing.confirmedCount()).isZero();
		assertThat(missing.remainingSlots()).isEqualTo(3L);
		// 집계가 있는 일정은 영향받지 않는다.
		assertThat(result.get(0).remainingSlots()).isEqualTo(2L);
	}

	private static WorkSchedule schedule(long id, JobPosting posting, String date) {
		WorkSchedule schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(LocalDate.parse(date),
			LocalDateTime.parse(date + "T08:00"), LocalDateTime.parse(date + "T16:00"), null, null));
		ReflectionTestUtils.setField(schedule, "id", id);
		return schedule;
	}
}

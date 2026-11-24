package com.wonday.farm.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// JOB-05 내 공고 조회 서비스의 단위 테스트. Repository는 mock이므로 실제 쿼리의 소유자 필터·JOIN 집계·정렬은
// 여기서 증명하지 않는다 (통합 검증 대상). 서비스가 받은 결과를 어떻게 조합하는지만 검증한다.
class JobPostingQueryServiceTest {
	private static final long EMPLOYER_ID = 10L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobPostingQueryService service = new JobPostingQueryService(members, postings, schedules);
	private Member employer;

	@BeforeEach
	void employerExists() {
		employer = member(EMPLOYER_ID, MemberRole.EMPLOYER);
	}

	@Nested
	class Authorization {
		@Test
		void rejectsMissingMemberWithoutQueryingPostings() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());

			assertRejectedWithoutQuerying();
		}

		@Test
		void rejectsWorker() {
			member(EMPLOYER_ID, MemberRole.WORKER);

			assertRejectedWithoutQuerying();
		}

		@Test
		void rejectsMemberWhoseRoleChangedToWorkerAfterSessionWasIssued() {
			// 세션 발급 당시에는 EMPLOYER였지만 현재 DB 역할이 WORKER로 바뀐 상황.
			ReflectionTestUtils.setField(employer, "role", MemberRole.WORKER.name());

			assertRejectedWithoutQuerying();
		}

		@Test
		void rejectsUnsupportedRoleCode() {
			ReflectionTestUtils.setField(employer, "role", "ADMIN");

			assertRejectedWithoutQuerying();
		}

		@Test
		void checksRoleOfTheSessionMemberId() {
			given(postings.findByEmployer_IdOrderByIdDesc(EMPLOYER_ID)).willReturn(List.of());

			service.findMine(EMPLOYER_ID);

			verify(members).findById(EMPLOYER_ID);
		}

		private void assertRejectedWithoutQuerying() {
			assertThatThrownBy(() -> service.findMine(EMPLOYER_ID)).isInstanceOf(AccessDeniedException.class);
			verify(members).findById(EMPLOYER_ID);
			verifyNoInteractions(postings, schedules);
		}
	}

	@Nested
	class OwnershipScope {
		@Test
		void usesSessionMemberIdForPostingsSchedulesAndCounts() {
			JobPosting posting = posting(1L, 2);
			WorkSchedule schedule = schedule(11L, posting, "2026-12-01");
			stub(List.of(posting), List.of(schedule), List.of(counts(11L, 0, 0)));

			service.findMine(EMPLOYER_ID);

			verify(members).findById(EMPLOYER_ID);
			verify(postings).findByEmployer_IdOrderByIdDesc(EMPLOYER_ID);
			verify(schedules).findOwnedSchedules(EMPLOYER_ID);
			verify(schedules).countOwnedSchedules(EMPLOYER_ID);
			verifyNoMoreInteractions(members, postings, schedules);
		}

		@Test
		void responseContainsExactlyThePostingsReturnedForTheEmployerInTheirOrder() {
			JobPosting newer = posting(3L, 1);
			JobPosting older = posting(1L, 1);
			stub(List.of(newer, older), List.of(), List.of());

			var response = service.findMine(EMPLOYER_ID);

			assertThat(response.jobPostings()).extracting(MyJobPostingsResponse.Posting::jobPostingId).containsExactly(3L, 1L);
		}

		@Test
		void interleavedSchedulesAreAttachedOnlyToTheirOwnPosting() {
			JobPosting a = posting(1L, 2);
			JobPosting b = posting(2L, 5);
			// 저장소가 공고와 무관한 순서(날짜순)로 섞어서 돌려줘도 각 공고에는 자기 일정만 붙는다.
			List<WorkSchedule> mixed = List.of(
				schedule(21L, b, "2026-12-01"), schedule(11L, a, "2026-12-02"),
				schedule(22L, b, "2026-12-03"), schedule(12L, a, "2026-12-04"), schedule(23L, b, "2026-12-05"));
			stub(List.of(b, a), mixed, List.of(counts(11L, 0, 0), counts(12L, 0, 0), counts(21L, 0, 0),
				counts(22L, 0, 0), counts(23L, 0, 0)));

			var response = service.findMine(EMPLOYER_ID);

			assertThat(scheduleIds(response, 2L)).containsExactly(21L, 22L, 23L);
			assertThat(scheduleIds(response, 1L)).containsExactly(11L, 12L);
		}

		@Test
		void scheduleOfAPostingNotInTheOwnedListIsNotShown() {
			// 두 조회 사이의 불일치 방어: 공고 목록에 없는 공고의 일정은 어떤 공고에도 붙지 않는다.
			JobPosting owned = posting(1L, 2);
			JobPosting unknown = posting(99L, 2);
			stub(List.of(owned), List.of(schedule(11L, owned, "2026-12-01"), schedule(991L, unknown, "2026-12-01")),
				List.of(counts(11L, 0, 0), counts(991L, 0, 0)));

			var response = service.findMine(EMPLOYER_ID);

			assertThat(response.jobPostings()).singleElement()
				.satisfies(p -> assertThat(p.schedules()).extracting(MyJobPostingsResponse.Schedule::workScheduleId)
					.containsExactly(11L));
		}
	}

	@Nested
	class EmptyResults {
		@Test
		void noPostingsReturnsEmptyListAndSkipsScheduleQueries() {
			given(postings.findByEmployer_IdOrderByIdDesc(EMPLOYER_ID)).willReturn(List.of());

			var response = service.findMine(EMPLOYER_ID);

			assertThat(response).isNotNull();
			assertThat(response.jobPostings()).isNotNull().isEmpty();
			verifyNoInteractions(schedules);
		}

		@Test
		void postingWithoutSchedulesHasEmptyScheduleList() {
			JobPosting withSchedules = posting(1L, 2);
			JobPosting withoutSchedules = posting(2L, 2);
			stub(List.of(withoutSchedules, withSchedules), List.of(schedule(11L, withSchedules, "2026-12-01")),
				List.of(counts(11L, 0, 0)));

			var response = service.findMine(EMPLOYER_ID);

			assertThat(posting(response, 2L).schedules()).isNotNull().isEmpty();
			assertThat(posting(response, 1L).schedules()).hasSize(1);
		}

		@Test
		void postingsExistButNoScheduleAnywhere() {
			stub(List.of(posting(1L, 2)), List.of(), List.of());

			var response = service.findMine(EMPLOYER_ID);

			assertThat(response.jobPostings()).singleElement()
				.satisfies(p -> assertThat(p.schedules()).isNotNull().isEmpty());
		}
	}

	@Nested
	class FieldMapping {
		@Test
		void everyPostingAndScheduleFieldIsMappedFromStoredValues() {
			JobPosting posting = posting(7L, 3);
			WorkSchedule schedule = schedule(70L, posting, "2026-12-01");
			ReflectionTestUtils.setField(schedule, "plannedStartAt", LocalDateTime.parse("2026-12-01T08:00:00.123456"));
			ReflectionTestUtils.setField(schedule, "plannedEndAt", LocalDateTime.parse("2026-12-01T17:30"));
			ReflectionTestUtils.setField(schedule, "breakStartAt", LocalDateTime.parse("2026-12-01T12:00"));
			ReflectionTestUtils.setField(schedule, "breakEndAt", LocalDateTime.parse("2026-12-01T13:00"));
			ReflectionTestUtils.setField(schedule, "termsVersion", 4);
			stub(List.of(posting), List.of(schedule), List.of(counts(70L, 5, 1)));

			var p = service.findMine(EMPLOYER_ID).jobPostings().getFirst();

			assertThat(p.jobPostingId()).isEqualTo(7L);
			assertThat(p.title()).isEqualTo("공고 7");
			assertThat(p.description()).isEqualTo("설명 7");
			assertThat(p.cropCode()).isEqualTo("CROP-7");
			assertThat(p.workTypeCode()).isEqualTo("WORK-7");
			assertThat(p.workLocation()).isEqualTo("작업지 7");
			assertThat(p.timeZone()).isEqualTo("GMT+9");
			assertThat(p.headcount()).isEqualTo(3);
			assertThat(p.dailyWage()).isEqualTo(107000L);
			var s = p.schedules().getFirst();
			assertThat(s.workScheduleId()).isEqualTo(70L);
			assertThat(s.workDate()).isEqualTo(LocalDate.parse("2026-12-01"));
			assertThat(s.plannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T08:00:00.123456"));
			assertThat(s.plannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T17:30"));
			assertThat(s.breakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:00"));
			assertThat(s.breakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T13:00"));
			assertThat(s.status()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(s.termsVersion()).isEqualTo(4);
			assertThat(s.applicantCount()).isEqualTo(5L);
			assertThat(s.confirmedCount()).isEqualTo(1L);
			assertThat(s.remainingSlots()).isEqualTo(2L);
		}

		@Test
		void nullBreaksAreReturnedAsNull() {
			JobPosting posting = posting(1L, 2);
			WorkSchedule schedule = schedule(11L, posting, "2026-12-01");
			ReflectionTestUtils.setField(schedule, "breakStartAt", null);
			ReflectionTestUtils.setField(schedule, "breakEndAt", null);
			stub(List.of(posting), List.of(schedule), List.of(counts(11L, 0, 0)));

			var s = service.findMine(EMPLOYER_ID).jobPostings().getFirst().schedules().getFirst();

			assertThat(s.breakStartAt()).isNull();
			assertThat(s.breakEndAt()).isNull();
		}

		@Test
		void legacyPostingWithNullTimeZoneKeepsNull() {
			// 기존 공고의 time_zone NULL을 GMT+9 등으로 채우지 않는다.
			JobPosting legacy = posting(1L, 2);
			ReflectionTestUtils.setField(legacy, "timeZone", null);
			stub(List.of(legacy), List.of(), List.of());

			assertThat(service.findMine(EMPLOYER_ID).jobPostings().getFirst().timeZone()).isNull();
		}

		@Test
		void storedStatusAndTermsVersionAreReturnedAsIs() {
			JobPosting posting = posting(1L, 2);
			WorkSchedule open = schedule(11L, posting, "2026-12-01");
			WorkSchedule closed = schedule(12L, posting, "2026-12-02");
			WorkSchedule removed = schedule(13L, posting, "2026-12-03");
			ReflectionTestUtils.setField(closed, "status", WorkSchedule.Status.CLOSED);
			ReflectionTestUtils.setField(removed, "status", WorkSchedule.Status.REMOVED);
			ReflectionTestUtils.setField(closed, "termsVersion", 2);
			ReflectionTestUtils.setField(removed, "termsVersion", 3);
			// 남은 자리가 있어도 CLOSED·REMOVED를 OPEN으로 바꾸지 않는다.
			stub(List.of(posting), List.of(open, closed, removed),
				List.of(counts(11L, 0, 0), counts(12L, 0, 0), counts(13L, 0, 0)));

			var result = service.findMine(EMPLOYER_ID).jobPostings().getFirst().schedules();

			assertThat(result).extracting(MyJobPostingsResponse.Schedule::status).containsExactly(
				WorkSchedule.Status.OPEN, WorkSchedule.Status.CLOSED, WorkSchedule.Status.REMOVED);
			assertThat(result).extracting(MyJobPostingsResponse.Schedule::termsVersion).containsExactly(1, 2, 3);
			assertThat(result).extracting(MyJobPostingsResponse.Schedule::remainingSlots).containsOnly(2L);
		}

		@Test
		void codesAreReturnedWithoutTranslation() {
			// 작목·작업 유형 코드 목록은 미정이므로 저장값을 그대로 돌려주는지만 본다.
			JobPosting posting = posting(1L, 2);
			ReflectionTestUtils.setField(posting, "cropCode", "임의-작목 코드");
			ReflectionTestUtils.setField(posting, "workTypeCode", "x");
			stub(List.of(posting), List.of(), List.of());

			var p = service.findMine(EMPLOYER_ID).jobPostings().getFirst();

			assertThat(p.cropCode()).isEqualTo("임의-작목 코드");
			assertThat(p.workTypeCode()).isEqualTo("x");
		}
	}

	@Nested
	class CountsAndRemainingSlots {
		@Test
		void eachScheduleIsCalculatedIndependently() {
			JobPosting posting = posting(1L, 3);
			stub(List.of(posting),
				List.of(schedule(11L, posting, "2026-12-01"), schedule(12L, posting, "2026-12-02"),
					schedule(13L, posting, "2026-12-03"), schedule(14L, posting, "2026-12-04"),
					schedule(15L, posting, "2026-12-05")),
				List.of(counts(11L, 0, 0), counts(12L, 4, 0), counts(13L, 4, 1), counts(14L, 5, 3), counts(15L, 9, 7)));

			var result = schedulesOf(service.findMine(EMPLOYER_ID), 1L);

			// 지원 0·확정 0 / 지원만 있음 / 일부 확정 / 정원만큼 확정 / 정원 초과 집계
			assertThat(result).extracting(MyJobPostingsResponse.Schedule::applicantCount).containsExactly(0L, 4L, 4L, 5L, 9L);
			assertThat(result).extracting(MyJobPostingsResponse.Schedule::confirmedCount).containsExactly(0L, 0L, 1L, 3L, 7L);
			assertThat(result).extracting(MyJobPostingsResponse.Schedule::remainingSlots).containsExactly(3L, 3L, 2L, 0L, 0L);
		}

		@Test
		void eachPostingUsesItsOwnHeadcount() {
			JobPosting small = posting(1L, 1);
			JobPosting large = posting(2L, 10);
			stub(List.of(large, small),
				List.of(schedule(11L, small, "2026-12-01"), schedule(21L, large, "2026-12-01")),
				List.of(counts(11L, 2, 1), counts(21L, 2, 1)));

			var response = service.findMine(EMPLOYER_ID);

			assertThat(schedulesOf(response, 1L).getFirst().remainingSlots()).isZero();
			assertThat(schedulesOf(response, 2L).getFirst().remainingSlots()).isEqualTo(9L);
		}

		@Test
		void countsAreMatchedByScheduleIdRegardlessOfReturnOrder() {
			JobPosting posting = posting(1L, 5);
			List<WorkSchedule> owned = List.of(schedule(11L, posting, "2026-12-01"), schedule(12L, posting, "2026-12-02"),
				schedule(13L, posting, "2026-12-03"));
			List<WorkScheduleRepository.ScheduleCounts> counts = new ArrayList<>(List.of(
				counts(11L, 1, 0), counts(12L, 2, 1), counts(13L, 3, 2)));

			stub(List.of(posting), owned, counts);
			var inOrder = schedulesOf(service.findMine(EMPLOYER_ID), 1L);
			Collections.reverse(counts);
			stub(List.of(posting), owned, counts);
			var reversed = schedulesOf(service.findMine(EMPLOYER_ID), 1L);

			assertThat(reversed).isEqualTo(inOrder);
			assertThat(inOrder).extracting(MyJobPostingsResponse.Schedule::applicantCount).containsExactly(1L, 2L, 3L);
			assertThat(inOrder).extracting(MyJobPostingsResponse.Schedule::remainingSlots).containsExactly(5L, 4L, 3L);
		}

		@Test
		void longCountsAreNotTruncatedToInt() {
			JobPosting posting = posting(1L, 2);
			long beyondInt = Integer.MAX_VALUE + 5L;
			stub(List.of(posting), List.of(schedule(11L, posting, "2026-12-01")), List.of(counts(11L, beyondInt, beyondInt)));

			var s = schedulesOf(service.findMine(EMPLOYER_ID), 1L).getFirst();

			assertThat(s.applicantCount()).isEqualTo(beyondInt);
			assertThat(s.confirmedCount()).isEqualTo(beyondInt);
			// (int)로 잘렸다면 음수 확정 인원이 되어 남은 자리가 정원보다 커진다.
			assertThat(s.remainingSlots()).isZero();
		}

		@Test
		void largeHeadcountMinusConfirmedIsComputedInLong() {
			JobPosting posting = posting(1L, Integer.MAX_VALUE);
			stub(List.of(posting), List.of(schedule(11L, posting, "2026-12-01")), List.of(counts(11L, 0, 0)));

			assertThat(schedulesOf(service.findMine(EMPLOYER_ID), 1L).getFirst().remainingSlots())
				.isEqualTo(Integer.MAX_VALUE);
		}
	}

	@Nested
	class ReadOnly {
		@Test
		void queryDoesNotSaveOrDeleteAnything() {
			JobPosting posting = posting(1L, 2);
			WorkSchedule schedule = schedule(11L, posting, "2026-12-01");
			ReflectionTestUtils.setField(schedule, "status", WorkSchedule.Status.CLOSED);
			stub(List.of(posting), List.of(schedule), List.of(counts(11L, 3, 0)));

			service.findMine(EMPLOYER_ID);

			// 조회 메서드 외 호출(save·delete·flush 등)이 없다.
			verify(members).findById(EMPLOYER_ID);
			verify(postings).findByEmployer_IdOrderByIdDesc(EMPLOYER_ID);
			verify(schedules).findOwnedSchedules(EMPLOYER_ID);
			verify(schedules).countOwnedSchedules(EMPLOYER_ID);
			verifyNoMoreInteractions(members, postings, schedules);
			// 남은 자리가 있어도 엔티티 상태를 바꾸지 않는다 (재모집·자리 복원 정책 없음).
			assertThat(schedule.getStatus()).isEqualTo(WorkSchedule.Status.CLOSED);
			assertThat(posting.getHeadcount()).isEqualTo(2);
			assertThat(schedule.getTermsVersion()).isEqualTo(1);
		}

		@Test
		void readOnlyRepeatableReadTransactionIsDeclared() throws Exception {
			// 선언만 확인한다. 실제 격리 수준의 동작은 DB 통합 검증 대상이다.
			var tx = JobPostingQueryService.class.getMethod("findMine", long.class)
				.getAnnotation(org.springframework.transaction.annotation.Transactional.class);

			assertThat(tx.readOnly()).isTrue();
			assertThat(tx.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
		}
	}

	// ---- fixtures ----

	private Member member(long id, MemberRole role) {
		Member member = new Member("employer-" + id, "hash", "고용주", role);
		ReflectionTestUtils.setField(member, "id", id);
		given(members.findById(id)).willReturn(Optional.of(member));
		return member;
	}

	private JobPosting posting(long id, int headcount) {
		var request = new CreateJobPostingRequest("공고 " + id, "설명 " + id, "CROP-" + id, "WORK-" + id, "작업지 " + id,
			"GMT+9", headcount, 100000L + id * 1000, List.of());
		JobPosting posting = new JobPosting(employer, request);
		ReflectionTestUtils.setField(posting, "id", id);
		return posting;
	}

	private static WorkSchedule schedule(long id, JobPosting posting, String date) {
		var request = new CreateJobPostingRequest.Schedule(LocalDate.parse(date), LocalDateTime.parse(date + "T08:00"),
			LocalDateTime.parse(date + "T16:00"), LocalDateTime.parse(date + "T12:00"), LocalDateTime.parse(date + "T13:00"));
		WorkSchedule schedule = new WorkSchedule(posting, request);
		ReflectionTestUtils.setField(schedule, "id", id);
		return schedule;
	}

	record Counts(Long getWorkScheduleId, long getApplicantCount, long getConfirmedCount)
		implements WorkScheduleRepository.ScheduleCounts {}

	private static WorkScheduleRepository.ScheduleCounts counts(long scheduleId, long applicants, long confirmed) {
		return new Counts(scheduleId, applicants, confirmed);
	}

	private void stub(List<JobPosting> owned, List<WorkSchedule> ownedSchedules,
			List<WorkScheduleRepository.ScheduleCounts> counts) {
		given(postings.findByEmployer_IdOrderByIdDesc(EMPLOYER_ID)).willReturn(owned);
		given(schedules.findOwnedSchedules(EMPLOYER_ID)).willReturn(ownedSchedules);
		given(schedules.countOwnedSchedules(EMPLOYER_ID)).willReturn(List.copyOf(counts));
	}

	private static MyJobPostingsResponse.Posting posting(MyJobPostingsResponse response, long postingId) {
		return response.jobPostings().stream().filter(p -> p.jobPostingId() == postingId).findFirst().orElseThrow();
	}

	private static List<MyJobPostingsResponse.Schedule> schedulesOf(MyJobPostingsResponse response, long postingId) {
		return posting(response, postingId).schedules();
	}

	private static List<Long> scheduleIds(MyJobPostingsResponse response, long postingId) {
		return schedulesOf(response, postingId).stream().map(MyJobPostingsResponse.Schedule::workScheduleId).toList();
	}
}

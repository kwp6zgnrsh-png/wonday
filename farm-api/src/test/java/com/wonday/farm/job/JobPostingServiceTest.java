package com.wonday.farm.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

class JobPostingServiceTest {
	private static final long EMPLOYER_ID = 10L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobPostingService service = new JobPostingService(members, postings, schedules);
	private final AtomicLong ids = new AtomicLong(100);

	@BeforeEach
	void persistenceAssignsIds() {
		given(postings.save(any())).willAnswer(invocation -> withId(invocation.getArgument(0)));
		given(schedules.saveAll(anyList())).willAnswer(invocation -> {
			List<WorkSchedule> saved = new ArrayList<>();
			for (Object schedule : (Iterable<?>) invocation.getArgument(0)) {
				saved.add((WorkSchedule) withId(schedule));
			}
			return saved;
		});
		memberWithRole(MemberRole.EMPLOYER);
	}

	@Nested
	class Success {
		@Test
		void savesPostingThenSchedulesAndMapsResponse() {
			var request = request("Asia/Seoul",
				schedule("2026-11-30", "2026-11-30T08:00", "2026-11-30T16:00", "2026-11-30T12:00", "2026-11-30T13:00"),
				schedule("2026-12-02", "2026-12-02T08:00", "2026-12-02T12:00", null, null));

			CreateJobPostingResponse response = service.create(EMPLOYER_ID, request);

			var order = inOrder(postings, schedules);
			ArgumentCaptor<JobPosting> posting = ArgumentCaptor.forClass(JobPosting.class);
			order.verify(postings).save(posting.capture());
			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<WorkSchedule>> saved = ArgumentCaptor.forClass(List.class);
			order.verify(schedules).saveAll(saved.capture());

			JobPosting p = posting.getValue();
			assertThat(p.getEmployer().getId()).isEqualTo(EMPLOYER_ID);
			assertThat(p.getTitle()).isEqualTo(request.title());
			assertThat(p.getDescription()).isEqualTo(request.description());
			assertThat(p.getCropCode()).isEqualTo(request.cropCode());
			assertThat(p.getWorkTypeCode()).isEqualTo(request.workTypeCode());
			assertThat(p.getWorkLocation()).isEqualTo(request.workLocation());
			assertThat(p.getTimeZone()).isEqualTo("Asia/Seoul");
			assertThat(p.getHeadcount()).isEqualTo(2);
			assertThat(p.getDailyWage()).isEqualTo(120000L);

			assertThat(saved.getValue()).hasSize(2).allSatisfy(s -> {
				assertThat(s.getJobPosting()).isSameAs(p);
				assertThat(s.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
				assertThat(s.getTermsVersion()).isEqualTo(1);
			});
			WorkSchedule first = saved.getValue().getFirst();
			assertThat(first.getWorkDate()).isEqualTo(LocalDate.parse("2026-11-30"));
			assertThat(first.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-11-30T08:00"));
			assertThat(first.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-11-30T16:00"));
			assertThat(first.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-11-30T12:00"));
			assertThat(first.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-11-30T13:00"));
			assertThat(saved.getValue().get(1).getBreakStartAt()).isNull();
			assertThat(saved.getValue().get(1).getBreakEndAt()).isNull();

			assertThat(response.jobPostingId()).isEqualTo(p.getId());
			assertThat(response.employerId()).isEqualTo(EMPLOYER_ID);
			assertThat(response.headcount()).isEqualTo(2);
			assertThat(response.schedules()).extracting(CreateJobPostingResponse.Schedule::workScheduleId)
				.containsExactly(saved.getValue().get(0).getId(), saved.getValue().get(1).getId());
			assertThat(response.schedules()).extracting(CreateJobPostingResponse.Schedule::workDate)
				.containsExactly(LocalDate.parse("2026-11-30"), LocalDate.parse("2026-12-02"));
			assertThat(response.schedules()).extracting(CreateJobPostingResponse.Schedule::status)
				.containsOnly(WorkSchedule.Status.OPEN);
		}

		@Test
		void acceptsOvernightShiftAndBreakTouchingWorkBoundaries() {
			service.create(EMPLOYER_ID, request("Asia/Seoul",
				schedule("2026-12-05", "2026-12-05T22:00", "2026-12-06T06:00", "2026-12-05T22:00", "2026-12-05T23:00"),
				schedule("2026-12-06", "2026-12-06T08:00", "2026-12-06T16:00", "2026-12-06T15:00", "2026-12-06T16:00")));

			verify(schedules).saveAll(anyList());
		}

		@Test
		void acceptsDatetimeRangeLimitsAndMicroseconds() {
			service.create(EMPLOYER_ID, request("Asia/Seoul",
				schedule("1000-01-01", "1000-01-01T00:00", "1000-01-01T01:00", null, null),
				schedule("9999-12-31", "9999-12-31T22:00", "9999-12-31T23:59:59.999999", null, null)));

			verify(schedules).saveAll(anyList());
		}

		@Test
		void acceptsDescriptionOfExactly65535Utf8Bytes() {
			// "가"는 UTF-8 3바이트: 21845 × 3 = 65535
			service.create(EMPLOYER_ID, request("Asia/Seoul", "가".repeat(21845), defaultSchedule()));

			verify(postings).save(any());
		}

		@Test
		void acceptsTimesOutsideDstTransition() {
			service.create(EMPLOYER_ID, request("America/New_York",
				schedule("2026-03-08", "2026-03-08T03:00", "2026-03-08T11:00", null, null)));

			verify(postings).save(any());
		}
	}

	@Nested
	class Authorization {
		@Test
		void rejectsMemberWhoseCurrentRoleIsWorker() {
			memberWithRole(MemberRole.WORKER);

			assertRejectedWithoutSaving(() -> service.create(EMPLOYER_ID, request("Asia/Seoul", defaultSchedule())));
		}

		@Test
		void rejectsMissingMember() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());

			assertRejectedWithoutSaving(() -> service.create(EMPLOYER_ID, request("Asia/Seoul", defaultSchedule())));
		}

		@Test
		void rejectsUnsupportedRoleCode() {
			Member member = memberWithRole(MemberRole.EMPLOYER);
			ReflectionTestUtils.setField(member, "role", "ADMIN");

			assertRejectedWithoutSaving(() -> service.create(EMPLOYER_ID, request("Asia/Seoul", defaultSchedule())));
		}

		@Test
		void checksRoleBeforeValidatingSchedules() {
			// 역할이 바뀐 회원은 입력 오류보다 권한 오류를 먼저 받는다.
			memberWithRole(MemberRole.WORKER);

			assertRejectedWithoutSaving(() -> service.create(EMPLOYER_ID, request("Not/AZone", defaultSchedule())));
		}

		private void assertRejectedWithoutSaving(Runnable call) {
			assertThatThrownBy(call::run).isInstanceOf(AccessDeniedException.class);
			verify(postings, never()).save(any());
			verify(schedules, never()).saveAll(anyList());
		}
	}

	@Nested
	class Validation {
		@Test
		void invalidTimeZone() {
			assertInvalid(request("Mars/Olympus", defaultSchedule()), "INVALID_TIME_ZONE", "timeZone");
		}

		@Test
		void descriptionOver65535Utf8Bytes() {
			assertInvalid(request("Asia/Seoul", "가".repeat(21845) + "d", defaultSchedule()),
				"INVALID_JOB_POSTING", "description");
		}

		@Test
		void duplicateWorkDateReportsSecondOccurrence() {
			assertInvalid(request("Asia/Seoul",
					schedule("2026-12-11", "2026-12-11T08:00", "2026-12-11T12:00", null, null),
					schedule("2026-12-12", "2026-12-12T08:00", "2026-12-12T12:00", null, null),
					schedule("2026-12-11", "2026-12-11T13:00", "2026-12-11T17:00", null, null)),
				"DUPLICATE_WORK_DATE", "schedules[2].workDate");
		}

		@Test
		void workDateDifferentFromStartDate() {
			assertInvalid(one("2026-12-12", "2026-12-11T08:00", "2026-12-11T12:00", null, null),
				"INVALID_WORK_TIME", "schedules[0].workDate");
		}

		@Test
		void endEqualToStart() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T08:00", null, null),
				"INVALID_WORK_TIME", "schedules[0].plannedEndAt");
		}

		@Test
		void endBeforeStart() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T07:00", null, null),
				"INVALID_WORK_TIME", "schedules[0].plannedEndAt");
		}

		@Test
		void breakStartMissing() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", null, "2026-12-11T13:00"),
				"INVALID_BREAK_TIME", "schedules[0].breakStartAt");
		}

		@Test
		void breakEndMissingIsRejected() {
			// field 값은 DEFECT-4 (JobPostingKnownDefectTests)에서 별도로 검증한다.
			assertThatThrownBy(() -> service.create(EMPLOYER_ID,
					one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T12:00", null)))
				.isInstanceOfSatisfying(JobPostingException.class,
					e -> assertThat(e.code()).isEqualTo("INVALID_BREAK_TIME"));
		}

		@Test
		void breakEndEqualToStart() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T12:00", "2026-12-11T12:00"),
				"INVALID_BREAK_TIME", "schedules[0].breakEndAt");
		}

		@Test
		void breakEndBeforeStart() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T13:00", "2026-12-11T12:00"),
				"INVALID_BREAK_TIME", "schedules[0].breakEndAt");
		}

		@Test
		void breakStartsBeforeWorkIsRejected() {
			// field 값은 DEFECT-4에서 별도로 검증한다.
			assertThatThrownBy(() -> service.create(EMPLOYER_ID,
					one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T07:59", "2026-12-11T09:00")))
				.isInstanceOfSatisfying(JobPostingException.class,
					e -> assertThat(e.code()).isEqualTo("INVALID_BREAK_TIME"));
		}

		@Test
		void breakEndsAfterWork() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T15:00", "2026-12-11T16:01"),
				"INVALID_BREAK_TIME", "schedules[0].breakEndAt");
		}

		@Test
		void yearBelowMysqlDatetimeRange() {
			assertInvalid(one("0999-12-31", "0999-12-31T08:00", "0999-12-31T12:00", null, null),
				"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt");
		}

		@Test
		void yearAboveMysqlDatetimeRange() {
			assertInvalid(one("9999-12-31", "9999-12-31T22:00", "+10000-01-01T02:00", null, null),
				"INVALID_LOCAL_TIME", "schedules[0].plannedEndAt");
		}

		@Test
		void subMicrosecondPrecision() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00:00.0000001", "2026-12-11T12:00", null, null),
				"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt");
		}

		@Test
		void subMicrosecondBreakEnd() {
			assertInvalid(one("2026-12-11", "2026-12-11T08:00", "2026-12-11T16:00", "2026-12-11T12:00", "2026-12-11T13:00:00.000000001"),
				"INVALID_LOCAL_TIME", "schedules[0].breakEndAt");
		}

		@Test
		void nonexistentLocalTimeInDstGap() {
			assertInvalid(request("America/New_York",
					schedule("2026-03-08", "2026-03-08T02:30", "2026-03-08T10:00", null, null)),
				"INVALID_LOCAL_TIME", "schedules[0].plannedStartAt");
		}

		@Test
		void ambiguousLocalTimeInDstOverlap() {
			assertInvalid(request("America/New_York",
					schedule("2026-10-31", "2026-10-31T22:00", "2026-11-01T01:30", null, null)),
				"INVALID_LOCAL_TIME", "schedules[0].plannedEndAt");
		}

		@Test
		void ambiguousBreakTimeInDstOverlap() {
			assertInvalid(request("America/New_York",
					schedule("2026-10-31", "2026-10-31T20:00", "2026-11-01T04:00", "2026-11-01T01:15", "2026-11-01T03:00")),
				"INVALID_LOCAL_TIME", "schedules[0].breakStartAt");
		}

		@Test
		void invalidLaterScheduleStopsWholeRequestBeforeSaving() {
			assertInvalid(request("Asia/Seoul",
					schedule("2026-12-13", "2026-12-13T08:00", "2026-12-13T12:00", null, null),
					schedule("2026-12-14", "2026-12-14T08:00", "2026-12-14T07:00", null, null)),
				"INVALID_WORK_TIME", "schedules[1].plannedEndAt");
		}

		private void assertInvalid(CreateJobPostingRequest request, String code, String field) {
			assertThatThrownBy(() -> service.create(EMPLOYER_ID, request))
				.isInstanceOfSatisfying(JobPostingException.class, e -> {
					assertThat(e.code()).isEqualTo(code);
					assertThat(e.field()).isEqualTo(field);
					assertThat(e.getMessage()).isNotBlank();
				});
			verify(postings, never()).save(any());
			verify(schedules, never()).saveAll(anyList());
		}
	}

	// ---- fixtures ----

	private Member memberWithRole(MemberRole role) {
		Member member = new Member("employer", "hash", "고용주", role);
		ReflectionTestUtils.setField(member, "id", EMPLOYER_ID);
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member));
		return member;
	}

	private Object withId(Object entity) {
		ReflectionTestUtils.setField(entity, "id", ids.incrementAndGet());
		return entity;
	}

	static CreateJobPostingRequest request(String timeZone, CreateJobPostingRequest.Schedule... schedules) {
		return request(timeZone, "사과 수확과 운반 작업입니다.", schedules);
	}

	static CreateJobPostingRequest request(String timeZone, String description, CreateJobPostingRequest.Schedule... schedules) {
		return new CreateJobPostingRequest("사과 수확 근로자 모집", description, JobPostingRequests.CROP_CODE,
			JobPostingRequests.WORK_TYPE_CODE, "충청북도 충주시 예시농장", timeZone, 2, 120000L, List.of(schedules));
	}

	static CreateJobPostingRequest one(String date, String start, String end, String breakStart, String breakEnd) {
		return request("Asia/Seoul", schedule(date, start, end, breakStart, breakEnd));
	}

	static CreateJobPostingRequest.Schedule defaultSchedule() {
		return schedule("2026-11-30", "2026-11-30T08:00", "2026-11-30T16:00", null, null);
	}

	static CreateJobPostingRequest.Schedule schedule(String date, String start, String end, String breakStart, String breakEnd) {
		return new CreateJobPostingRequest.Schedule(LocalDate.parse(date), LocalDateTime.parse(start),
			LocalDateTime.parse(end), breakStart == null ? null : LocalDateTime.parse(breakStart),
			breakEnd == null ? null : LocalDateTime.parse(breakEnd));
	}
}

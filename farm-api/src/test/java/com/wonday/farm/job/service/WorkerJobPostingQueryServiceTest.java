package com.wonday.farm.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.WorkerJobPostingResponse;
import com.wonday.farm.job.dto.WorkerJobPostingsResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkerJobPostingException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// APP-01 근로자 공고 목록·상세 조회 서비스 단위 테스트.
// 저장소는 mock이며, "후보 공고는 ID 내림차순, 일정은 공고 ID 내림차순·근무일·일정 ID 오름차순으로 돌려준다"는
// 저장소 계약을 흉내 내는 가짜 응답을 쓴다. 실제 SQL의 정렬·EXISTS 조건·확정 집계(CANCELLED 제외 등)·
// 본인 지원 조회는 실행하지 않으므로 검증하지 않는다 (미실행 통합 검증 대상).
// 공고·일정·회원은 실제 도메인 객체, 코드 사전은 실제 classpath JSON을 읽는 AgriculturalCodeCatalog를 쓴다.
@DisplayName("APP-01 근로자 공고 조회 서비스")
class WorkerJobPostingQueryServiceTest {
	private static final long WORKER_ID = 5L;
	// 서버 시각: 2026-11-25T00:00:00Z = GMT+9 2026-11-25 09:00
	private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
	private static final AgriculturalCodeCatalog CODES = new AgriculturalCodeCatalog();

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final List<JobPosting> allPostings = new ArrayList<>();
	private final List<WorkSchedule> allSchedules = new ArrayList<>();
	private final Map<Long, Long> counts = new HashMap<>();
	private final Map<Long, Application> applications = new HashMap<>();
	private Member worker;

	@BeforeEach
	void fakeRepositories() {
		worker = member(WORKER_ID, MemberRole.WORKER);
		given(postings.findRecruitmentCandidates(any(), any(), any(), any())).willAnswer(invocation -> {
			Long beforeId = invocation.getArgument(0);
			Pageable page = invocation.getArgument(3);
			return allPostings.stream().sorted(Comparator.comparing(JobPosting::getId).reversed())
				.filter(posting -> beforeId == null || posting.getId() < beforeId)
				.limit(page.getPageSize()).toList();
		});
		given(postings.findById(anyLong())).willAnswer(invocation -> allPostings.stream()
			.filter(posting -> posting.getId().equals(invocation.getArgument(0))).findFirst());
		given(schedules.findForWorkerPostings(anyCollection())).willAnswer(invocation -> {
			Collection<Long> ids = invocation.getArgument(0);
			return allSchedules.stream().filter(schedule -> ids.contains(schedule.getJobPosting().getId()))
				.sorted(Comparator.comparing((WorkSchedule schedule) -> schedule.getJobPosting().getId()).reversed()
					.thenComparing(WorkSchedule::getWorkDate).thenComparing(WorkSchedule::getId)).toList();
		});
		given(schedules.countWorkerScheduleAssignments(anyCollection())).willAnswer(invocation -> {
			Collection<Long> ids = invocation.getArgument(0);
			return ids.stream().filter(counts::containsKey).map(id -> (WorkScheduleRepository.OccupyingCount) new Count(id, counts.get(id))).toList();
		});
		given(schedules.findWorkerApplications(eq(WORKER_ID), anyCollection())).willAnswer(invocation -> {
			Collection<Long> ids = invocation.getArgument(1);
			return ids.stream().filter(applications::containsKey)
				.map(id -> (WorkScheduleRepository.WorkerApplication) applications.get(id)).toList();
		});
	}

	@Nested
	@DisplayName("1. 근로자 역할 확인")
	class WorkerRole {
		@Test
		@DisplayName("현재 DB 역할이 WORKER이면 목록과 상세를 조회할 수 있다")
		void workerCanListAndReadDetail() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings()).hasSize(1);
			assertThat(service().detail(WORKER_ID, 1).schedules()).hasSize(1);
		}

		@Test
		@DisplayName("EMPLOYER·회원 없음·해석할 수 없는 역할이면 거부하고 공고·일정을 조회하지 않는다")
		void nonWorkersAreDeniedBeforeAnyLookup() {
			ReflectionTestUtils.setField(worker, "role", MemberRole.EMPLOYER.name());
			assertDenied();
			ReflectionTestUtils.setField(worker, "role", "ADMIN");
			assertDenied();
			given(members.findById(WORKER_ID)).willReturn(Optional.empty());
			assertDenied();
			verifyNoInteractions(postings, schedules);
		}

		@Test
		@DisplayName("로그인 당시가 아니라 호출 시점의 DB 역할을 매번 다시 확인한다")
		void roleIsCheckedOnEveryCall() {
			service().list(WORKER_ID, 0, 20);
			ReflectionTestUtils.setField(worker, "role", MemberRole.EMPLOYER.name());

			assertThatThrownBy(() -> service().list(WORKER_ID, 0, 20)).isInstanceOf(AccessDeniedException.class);
			verify(members, times(2)).findById(WORKER_ID);
		}

		@Test
		@DisplayName("'내 공고'가 아니므로 여러 고용주의 공고가 함께 노출된다")
		void postingsOfDifferentEmployersAreListed() {
			var a = posting(1, "GMT+9", 3);
			var b = posting(2, "GMT+9", 3);
			schedule(11, a, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(21, b, "2026-11-26", WorkSchedule.Status.OPEN);

			var listed = service().list(WORKER_ID, 0, 20).jobPostings();

			assertThat(listed).extracting(WorkerJobPostingsResponse.Posting::jobPostingId).containsExactly(2L, 1L);
			assertThat(a.getEmployer().getId()).isNotEqualTo(b.getEmployer().getId());
		}

		@Test
		@DisplayName("트랜잭션은 읽기 전용 REPEATABLE_READ다")
		void readOnlyRepeatableRead() {
			var transactional = WorkerJobPostingQueryService.class.getAnnotation(Transactional.class);

			assertThat(transactional.readOnly()).isTrue();
			assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
		}

		private void assertDenied() {
			assertThatThrownBy(() -> service().list(WORKER_ID, 0, 20)).isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service().detail(WORKER_ID, 1)).isInstanceOf(AccessDeniedException.class);
		}
	}

	@Nested
	@DisplayName("2. 목록 노출과 날짜 필터링")
	class ListVisibility {
		@Test
		@DisplayName("시작 전 OPEN 날짜만 모집 날짜에 넣고, 상태가 섞인 공고는 정상 날짜만 포함한다")
		void onlyUpcomingOpenDatesAreIncluded() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-24", WorkSchedule.Status.OPEN);   // 이미 시작
			schedule(12, posting, "2026-11-26", WorkSchedule.Status.PAUSED);
			schedule(13, posting, "2026-11-27", WorkSchedule.Status.OPEN);
			schedule(14, posting, "2026-11-28", WorkSchedule.Status.CLOSED);
			schedule(15, posting, "2026-11-29", WorkSchedule.Status.REMOVED);
			schedule(16, posting, "2026-11-30", WorkSchedule.Status.OPEN);

			var listed = service().list(WORKER_ID, 0, 20).jobPostings().getFirst();

			assertThat(listed.recruitingWorkDates()).containsExactly(LocalDate.parse("2026-11-27"), LocalDate.parse("2026-11-30"));
			assertThat(listed.recruitingSchedules()).extracting(WorkerJobPostingsResponse.RecruitingSchedule::workScheduleId)
				.containsExactly(13L, 16L);
		}

		@Test
		@DisplayName("recruitingWorkDates와 recruitingSchedules는 같은 날짜를 같은 순서로 표현한다")
		void workDatesAndSchedulesDescribeTheSameDates() {
			var posting = posting(1, "GMT+9", 3);
			schedule(13, posting, "2026-11-28", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			var listed = service().list(WORKER_ID, 0, 20).jobPostings().getFirst();

			assertThat(listed.recruitingSchedules()).extracting(WorkerJobPostingsResponse.RecruitingSchedule::workDate)
				.containsExactlyElementsOf(listed.recruitingWorkDates());
		}

		@Test
		@DisplayName("모든 날짜가 중단·마감·취소·시작 후인 공고와 일정이 없는 공고는 제외한다")
		void postingsWithoutRecruitingDatesAreExcluded() {
			var closedOnly = posting(1, "GMT+9", 3);
			schedule(11, closedOnly, "2026-11-26", WorkSchedule.Status.PAUSED);
			schedule(12, closedOnly, "2026-11-27", WorkSchedule.Status.CLOSED);
			schedule(13, closedOnly, "2026-11-28", WorkSchedule.Status.REMOVED);
			schedule(14, closedOnly, "2026-11-20", WorkSchedule.Status.OPEN);
			posting(2, "GMT+9", 3);   // 일정 없음
			var visible = posting(3, "GMT+9", 3);
			schedule(31, visible, "2026-11-26", WorkSchedule.Status.OPEN);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings())
				.extracting(WorkerJobPostingsResponse.Posting::jobPostingId).containsExactly(3L);
		}

		@Test
		@DisplayName("정원 충족이나 본인의 지원·확정 이력이 있어도 공고를 숨기지 않는다")
		void fullOrAppliedPostingsStayVisible() {
			var full = posting(1, "GMT+9", 2);
			schedule(11, full, "2026-11-26", WorkSchedule.Status.OPEN);
			counts.put(11L, 2L);
			var applied = posting(2, "GMT+9", 2);
			schedule(21, applied, "2026-11-26", WorkSchedule.Status.OPEN);
			applications.put(21L, new Application(21L, "PENDING", null, null));

			var listed = service().list(WORKER_ID, 0, 20).jobPostings();

			assertThat(listed).extracting(WorkerJobPostingsResponse.Posting::jobPostingId).containsExactly(2L, 1L);
			assertThat(listed.get(1).recruitingSchedules().getFirst().unavailableReason()).isEqualTo("CAPACITY_FULL");
			assertThat(listed.get(0).recruitingSchedules().getFirst().unavailableReason()).isEqualTo("ALREADY_APPLIED");
		}

		@Test
		@DisplayName("빈 결과는 빈 배열과 hasNext=false다")
		void emptyResult() {
			var response = service().list(WORKER_ID, 0, 20);

			assertThat(response.jobPostings()).isEmpty();
			assertThat(response.hasNext()).isFalse();
			assertThat(response.page()).isZero();
			assertThat(response.size()).isEqualTo(20);
		}

		@Test
		@DisplayName("후보 조회는 OPEN 상태와 '서버 시각 - 18시간 1초'(초 절삭) 하한, 100개 배치로 호출한다")
		void candidateQueryArguments() {
			var clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00.987654321Z"), ZoneOffset.UTC);

			service(clock).list(WORKER_ID, 0, 20);

			verify(postings).findRecruitmentCandidates(isNull(), eq(WorkSchedule.Status.OPEN),
				eq(LocalDateTime.parse("2026-11-24T05:59:59")), eq(PageRequest.of(0, 100)));
		}
	}

	@Nested
	@DisplayName("3. 시작 시각과 시간대 경계")
	class StartTimeAndZone {
		// 대상 일정: GMT+9 2026-11-26 09:00 = UTC 2026-11-26T00:00:00Z
		private final Instant start = Instant.parse("2026-11-26T00:00:00Z");

		@Test
		@DisplayName("시작 1나노초 전에는 지원 가능하고 목록에 포함된다")
		void justBeforeStartIsApplicable() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			var service = service(Clock.fixed(start.minusNanos(1), ZoneOffset.UTC));

			assertThat(service.list(WORKER_ID, 0, 20).jobPostings()).hasSize(1);
			assertApplicable(service.detail(WORKER_ID, 1).schedules().getFirst());
		}

		@Test
		@DisplayName("시작과 정확히 같거나 이후면 SCHEDULE_STARTED이고 목록에서 제외된다")
		void atOrAfterStartIsStarted() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			for (Instant now : List.of(start, start.plusNanos(1), start.plusSeconds(3600))) {
				var service = service(Clock.fixed(now, ZoneOffset.UTC));
				assertThat(service.list(WORKER_ID, 0, 20).jobPostings()).as(now.toString()).isEmpty();
				assertUnavailable(service.detail(WORKER_ID, 1).schedules().getFirst(), "SCHEDULE_STARTED");
			}
		}

		@Test
		@DisplayName("GMT+9 09:00은 UTC 00:00이다. UTC 08:59는 이미 시작 후, 전날 UTC 23:59는 시작 전이다")
		void gmtPlus9IsComparedAsAnInstant() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			assertUnavailable(service(Clock.fixed(Instant.parse("2026-11-26T08:59:00Z"), ZoneOffset.UTC))
				.detail(WORKER_ID, 1).schedules().getFirst(), "SCHEDULE_STARTED");
			assertApplicable(service(Clock.fixed(Instant.parse("2026-11-25T23:59:00Z"), ZoneOffset.UTC))
				.detail(WORKER_ID, 1).schedules().getFirst());
		}

		@Test
		@DisplayName("JVM 기본 시간대가 바뀌어도 판정 결과가 같다")
		void doesNotDependOnJvmDefaultZone() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			var service = service(Clock.fixed(start.minusNanos(1), ZoneOffset.UTC));
			TimeZone original = TimeZone.getDefault();
			try {
				for (String zone : List.of("Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
					TimeZone.setDefault(TimeZone.getTimeZone(zone));
					assertApplicable(service.detail(WORKER_ID, 1).schedules().getFirst());
					assertThat(service.list(WORKER_ID, 0, 20).jobPostings()).as(zone).hasSize(1);
				}
			} finally {
				TimeZone.setDefault(original);
			}
		}

		@Test
		@DisplayName("기존 공고의 유효한 다른 시간대는 GMT+9로 바꾸지 않고 그 시간대로 판정한다")
		void otherValidZoneIsKept() {
			var posting = posting(1, "UTC", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			// UTC 공고의 현지 09:00 = UTC 09:00. UTC 08:59는 아직 시작 전이다.
			var detail = service(Clock.fixed(Instant.parse("2026-11-26T08:59:00Z"), ZoneOffset.UTC)).detail(WORKER_ID, 1);

			assertThat(detail.timeZone()).isEqualTo("UTC");
			assertApplicable(detail.schedules().getFirst());
			assertThat(posting.getTimeZone()).isEqualTo("UTC");
		}

		@ParameterizedTest(name = "timeZone=\"{0}\"")
		@NullSource
		@ValueSource(strings = {"", " ", "Seoul", "GMT+99", "Mars/Olympus"})
		@DisplayName("시간대가 null·공백·잘못된 값이면 상세는 TIME_ZONE_UNAVAILABLE로 유지하고 목록에서는 제외한다")
		void invalidZone(String zone) {
			var posting = posting(1, zone, 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings()).isEmpty();
			var detail = service().detail(WORKER_ID, 1);
			assertUnavailable(detail.schedules().getFirst(), "TIME_ZONE_UNAVAILABLE");
			assertThat(detail.timeZone()).isEqualTo(zone);
		}

		@Test
		@DisplayName("시작 시각 누락·DST 누락·DST 중복이면 SCHEDULE_START_TIME_UNAVAILABLE이고 목록에서는 제외한다")
		void unresolvableStart() {
			var newYork = posting(1, "America/New_York", 3);
			var gap = schedule(11, newYork, "2027-03-14", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(gap, "plannedStartAt", LocalDateTime.parse("2027-03-14T02:30"));
			var overlap = schedule(12, newYork, "2027-11-07", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(overlap, "plannedStartAt", LocalDateTime.parse("2027-11-07T01:30"));
			var missing = schedule(13, newYork, "2027-12-01", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(missing, "plannedStartAt", null);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings()).isEmpty();
			assertThat(service().detail(WORKER_ID, 1).schedules())
				.extracting(WorkerJobPostingResponse.Schedule::unavailableReason)
				.containsOnly("SCHEDULE_START_TIME_UNAVAILABLE");
		}

		@Test
		@DisplayName("해석 불가 날짜가 있어도 같은 공고의 정상 날짜는 목록에 남는다")
		void unresolvableDateDoesNotHideOtherDates() {
			var newYork = posting(1, "America/New_York", 3);
			var gap = schedule(11, newYork, "2027-03-14", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(gap, "plannedStartAt", LocalDateTime.parse("2027-03-14T02:30"));
			schedule(12, newYork, "2027-03-15", WorkSchedule.Status.OPEN);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings().getFirst().recruitingSchedules())
				.extracting(WorkerJobPostingsResponse.RecruitingSchedule::workScheduleId).containsExactly(12L);
		}

		@Test
		@DisplayName("한 요청에서는 서버 시각을 한 번만 읽어 모든 날짜에 같은 기준을 쓴다")
		void oneClockReadPerRequest() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-27", WorkSchedule.Status.OPEN);
			Clock clock = mock(Clock.class);
			// 두 번째 호출부터 시각이 크게 뒤로 가도 결과가 바뀌면 안 된다.
			given(clock.instant()).willReturn(NOW, Instant.parse("2030-01-01T00:00:00Z"));

			var listed = service(clock).list(WORKER_ID, 0, 20);

			assertThat(listed.jobPostings().getFirst().recruitingSchedules()).hasSize(2);
			verify(clock, times(1)).instant();

			Clock detailClock = mock(Clock.class);
			given(detailClock.instant()).willReturn(NOW, Instant.parse("2030-01-01T00:00:00Z"));
			assertThat(service(detailClock).detail(WORKER_ID, 1).schedules())
				.extracting(WorkerJobPostingResponse.Schedule::canApply).containsOnly(true);
			verify(detailClock, times(1)).instant();
		}

		@Test
		@DisplayName("마이크로초가 있는 시작 시각도 1마이크로초 차이까지 정확히 비교한다")
		void microsecondStartBoundary() {
			var posting = posting(1, "GMT+9", 3);
			var schedule = schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(schedule, "plannedStartAt", LocalDateTime.parse("2026-11-26T09:00:00.000001"));

			assertApplicable(service(Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC))
				.detail(WORKER_ID, 1).schedules().getFirst());
			assertUnavailable(service(Clock.fixed(Instant.parse("2026-11-26T00:00:00.000001Z"), ZoneOffset.UTC))
				.detail(WORKER_ID, 1).schedules().getFirst(), "SCHEDULE_STARTED");
		}
	}

	@Nested
	@DisplayName("4. 페이지 처리")
	class Paging {
		@ParameterizedTest(name = "page={0}")
		@ValueSource(ints = {-1, Integer.MIN_VALUE})
		@DisplayName("page가 음수이면 INVALID_PAGINATION(field=page)이고 공고를 조회하지 않는다")
		void negativePage(int page) {
			assertPagination(() -> service().list(WORKER_ID, page, 20), "page");
		}

		@ParameterizedTest(name = "size={0}")
		@ValueSource(ints = {0, -1, 101, Integer.MAX_VALUE})
		@DisplayName("size가 0·음수·101 이상이면 INVALID_PAGINATION(field=size)이다")
		void invalidSize(int size) {
			assertPagination(() -> service().list(WORKER_ID, 0, size), "size");
		}

		@Test
		@DisplayName("size 1과 100은 허용한다")
		void sizeBoundaries() {
			visiblePostings(150);

			assertThat(service().list(WORKER_ID, 0, 1).jobPostings()).hasSize(1);
			assertThat(service().list(WORKER_ID, 0, 100).jobPostings()).hasSize(100);
		}

		@Test
		@DisplayName("결과가 size 미만·동일·size+1일 때 반환 개수와 hasNext")
		void resultSizeAndHasNext() {
			visiblePostings(4);
			assertPage(service().list(WORKER_ID, 0, 5), 4, false);
			assertPage(service().list(WORKER_ID, 0, 4), 4, false);
			assertPage(service().list(WORKER_ID, 0, 3), 3, true);
		}

		@Test
		@DisplayName("첫 페이지·다음 페이지·범위 밖 페이지가 중복·누락 없이 이어진다")
		void pagesAreContiguous() {
			visiblePostings(5);   // ID 1..5 → 노출 순서 5,4,3,2,1

			assertThat(ids(service().list(WORKER_ID, 0, 2))).containsExactly(5L, 4L);
			assertThat(ids(service().list(WORKER_ID, 1, 2))).containsExactly(3L, 2L);
			var last = service().list(WORKER_ID, 2, 2);
			assertThat(ids(last)).containsExactly(1L);
			assertThat(last.hasNext()).isFalse();
			var beyond = service().list(WORKER_ID, 3, 2);
			assertThat(beyond.jobPostings()).isEmpty();
			assertThat(beyond.hasNext()).isFalse();
		}

		@Test
		@DisplayName("노출 제외 후보가 섞여 있어도 필터링 후 기준으로 페이지를 나눈다")
		void pagingAfterFiltering() {
			for (long id = 1; id <= 6; id++) {
				var posting = posting(id, "GMT+9", 3);
				// 짝수 ID는 시작 후 날짜만 있어 노출 제외된다.
				schedule(id * 10, posting, id % 2 == 0 ? "2026-11-20" : "2026-11-26", WorkSchedule.Status.OPEN);
			}

			assertThat(ids(service().list(WORKER_ID, 0, 2))).containsExactly(5L, 3L);
			var second = service().list(WORKER_ID, 1, 2);
			assertThat(ids(second)).containsExactly(1L);
			assertThat(second.hasNext()).isFalse();
		}

		@Test
		@DisplayName("후보가 100개를 넘으면 마지막 후보 ID를 beforeId로 다음 배치를 읽는다")
		void continuesWithNextBatch() {
			visiblePostings(150);   // ID 1..150

			var first = service().list(WORKER_ID, 0, 100);
			var second = service().list(WORKER_ID, 1, 100);

			assertThat(ids(first)).hasSize(100).startsWith(150L).endsWith(51L);
			assertThat(first.hasNext()).isTrue();
			assertThat(ids(second)).hasSize(50).startsWith(50L).endsWith(1L);
			assertThat(second.hasNext()).isFalse();
			var order = inOrder(postings);
			order.verify(postings).findRecruitmentCandidates(isNull(), any(), any(), eq(PageRequest.of(0, 100)));
			order.verify(postings).findRecruitmentCandidates(eq(51L), any(), any(), eq(PageRequest.of(0, 100)));
		}

		@Test
		@DisplayName("필요한 size+1개를 첫 배치에서 채우면 다음 배치를 읽지 않는다")
		void noExtraBatchWhenFilled() {
			visiblePostings(150);

			service().list(WORKER_ID, 0, 20);

			verify(postings, times(1)).findRecruitmentCandidates(any(), any(), any(), any());
			verify(schedules, times(1)).findForWorkerPostings(anyCollection());
		}

		@Test
		@DisplayName("배치가 100개 미만이면 더 읽지 않고 끝낸다")
		void shortBatchEndsTheScan() {
			visiblePostings(30);

			service().list(WORKER_ID, 0, 50);

			verify(postings, times(1)).findRecruitmentCandidates(any(), any(), any(), any());
		}

		@Test
		@DisplayName("후보가 정확히 100개면 다음 배치를 한 번 더 읽고 빈 결과에서 끝낸다")
		void exactBatchReadsOneMoreEmptyBatch() {
			visiblePostings(100);

			var response = service().list(WORKER_ID, 0, 100);

			assertPage(response, 100, false);
			verify(postings).findRecruitmentCandidates(eq(1L), any(), any(), any());
			verify(postings, times(2)).findRecruitmentCandidates(any(), any(), any(), any());
		}

		@Test
		@DisplayName("큰 page 값에서도 offset 계산이 int로 넘치지 않는다")
		void largePageDoesNotOverflow() {
			visiblePostings(3);
			// int로 곱하면 Integer.MAX_VALUE * 100 이 음수가 되어 첫 페이지가 잘못 반환된다.
			var response = service().list(WORKER_ID, Integer.MAX_VALUE, 100);

			assertThat(response.jobPostings()).isEmpty();
			assertThat(response.hasNext()).isFalse();
			assertThat(response.page()).isEqualTo(Integer.MAX_VALUE);
		}

		private void assertPagination(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
			assertThatThrownBy(call).isInstanceOfSatisfying(WorkerJobPostingException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(error.code()).isEqualTo("INVALID_PAGINATION");
				assertThat(error.field()).isEqualTo(field);
			});
			verifyNoInteractions(postings, schedules);
		}

		private void assertPage(WorkerJobPostingsResponse response, int count, boolean hasNext) {
			assertThat(response.jobPostings()).hasSize(count);
			assertThat(response.hasNext()).isEqualTo(hasNext);
		}

		private void visiblePostings(int count) {
			for (long id = 1; id <= count; id++) {
				schedule(id * 1000, posting(id, "GMT+9", 3), "2026-11-26", WorkSchedule.Status.OPEN);
			}
		}

		private List<Long> ids(WorkerJobPostingsResponse response) {
			return response.jobPostings().stream().map(WorkerJobPostingsResponse.Posting::jobPostingId).toList();
		}
	}

	@Nested
	@DisplayName("5. 상세 조회와 응답 보존")
	class Detail {
		@Test
		@DisplayName("전체 취소·마감·과거 날짜뿐인 공고도 상세를 반환하고 모든 일정을 유지한다")
		void detailIgnoresListVisibility() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-20", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-26", WorkSchedule.Status.CLOSED);
			schedule(13, posting, "2026-11-27", WorkSchedule.Status.REMOVED);
			schedule(14, posting, "2026-11-28", WorkSchedule.Status.PAUSED);

			assertThat(service().list(WORKER_ID, 0, 20).jobPostings()).isEmpty();
			assertThat(service().detail(WORKER_ID, 1).schedules()).extracting(WorkerJobPostingResponse.Schedule::unavailableReason)
				.containsExactly("SCHEDULE_STARTED", "RECRUITMENT_CLOSED", "RECRUITMENT_CANCELLED", "RECRUITMENT_PAUSED");
		}

		@Test
		@DisplayName("일정이 없으면 schedules=[]이고 집계·지원 조회를 하지 않는다")
		void noSchedules() {
			posting(1, "GMT+9", 3);

			assertThat(service().detail(WORKER_ID, 1).schedules()).isEmpty();
			verify(schedules, never()).countWorkerScheduleAssignments(anyCollection());
			verify(schedules, never()).findWorkerApplications(anyLong(), anyCollection());
		}

		@ParameterizedTest(name = "jobPostingId={0}")
		@ValueSource(longs = {0, -1, Long.MIN_VALUE})
		@DisplayName("0·음수 ID는 INVALID_REQUEST(field=jobPostingId)이고 공고를 조회하지 않는다")
		void nonPositiveId(long id) {
			assertThatThrownBy(() -> service().detail(WORKER_ID, id)).isInstanceOfSatisfying(WorkerJobPostingException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(error.code()).isEqualTo("INVALID_REQUEST");
				assertThat(error.field()).isEqualTo("jobPostingId");
			});
			verifyNoInteractions(postings, schedules);
		}

		@Test
		@DisplayName("존재하지 않는 양수 ID는 404 JOB_POSTING_NOT_FOUND다")
		void missingPosting() {
			assertThatThrownBy(() -> service().detail(WORKER_ID, 999)).isInstanceOfSatisfying(WorkerJobPostingException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(error.code()).isEqualTo("JOB_POSTING_NOT_FOUND");
			});
			verify(schedules, never()).findForWorkerPostings(anyCollection());
		}

		@Test
		@DisplayName("공고 정보와 모든 일정의 ID·날짜·근무/휴게 일시(마이크로초)·상태·버전을 그대로 반환한다")
		void preservesPostingAndScheduleFields() {
			var posting = posting(1, "GMT+9", 3);
			var withBreak = schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			ReflectionTestUtils.setField(withBreak, "plannedStartAt", LocalDateTime.parse("2026-11-26T09:00:00.000001"));
			ReflectionTestUtils.setField(withBreak, "plannedEndAt", LocalDateTime.parse("2026-11-26T18:00:00.999999"));
			ReflectionTestUtils.setField(withBreak, "breakStartAt", LocalDateTime.parse("2026-11-26T12:00:00.123456"));
			ReflectionTestUtils.setField(withBreak, "breakEndAt", LocalDateTime.parse("2026-11-26T13:00"));
			ReflectionTestUtils.setField(withBreak, "termsVersion", 7);
			schedule(12, posting, "2026-11-27", WorkSchedule.Status.CLOSED);

			var detail = service().detail(WORKER_ID, 1);

			assertThat(detail.jobPostingId()).isEqualTo(1L);
			assertThat(detail.title()).isEqualTo(posting.getTitle());
			assertThat(detail.description()).isEqualTo(posting.getDescription());
			assertThat(detail.workLocation()).isEqualTo(posting.getWorkLocation());
			assertThat(detail.timeZone()).isEqualTo("GMT+9");
			assertThat(detail.headcount()).isEqualTo(3);
			assertThat(detail.dailyWage()).isEqualTo(120000L);
			var first = detail.schedules().getFirst();
			assertThat(first.workScheduleId()).isEqualTo(11L);
			assertThat(first.workDate()).isEqualTo(LocalDate.parse("2026-11-26"));
			assertThat(first.plannedStartAt()).isEqualTo(LocalDateTime.parse("2026-11-26T09:00:00.000001"));
			assertThat(first.plannedEndAt()).isEqualTo(LocalDateTime.parse("2026-11-26T18:00:00.999999"));
			assertThat(first.breakStartAt()).isEqualTo(LocalDateTime.parse("2026-11-26T12:00:00.123456"));
			assertThat(first.breakEndAt()).isEqualTo(LocalDateTime.parse("2026-11-26T13:00"));
			assertThat(first.status()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(first.termsVersion()).isEqualTo(7);
			var second = detail.schedules().get(1);
			assertThat(second.workScheduleId()).isEqualTo(12L);
			assertThat(second.breakStartAt()).isNull();
			assertThat(second.breakEndAt()).isNull();
			assertThat(second.status()).isEqualTo(WorkSchedule.Status.CLOSED);
		}

		@Test
		@DisplayName("조회 중 일정의 상태·조건·버전을 바꾸지 않고 저장·삭제를 호출하지 않는다")
		void readOnlyLookup() {
			var posting = posting(1, "GMT+9", 2);
			var target = schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			counts.put(11L, 5L);
			var before = List.of(target.getStatus(), target.getTermsVersion(), target.getWorkDate(), target.getPlannedStartAt());

			service().detail(WORKER_ID, 1);
			service().list(WORKER_ID, 0, 20);

			assertThat(List.of(target.getStatus(), target.getTermsVersion(), target.getWorkDate(), target.getPlannedStartAt()))
				.isEqualTo(before);
			verify(postings).findById(1L);
			verify(postings).findRecruitmentCandidates(any(), any(), any(), any());
			verify(schedules, times(2)).findForWorkerPostings(anyCollection());
			verify(schedules, times(2)).countWorkerScheduleAssignments(anyCollection());
			verify(schedules, times(2)).findWorkerApplications(anyLong(), anyCollection());
			verifyNoMoreInteractions(postings, schedules);
		}

		@Test
		@DisplayName("응답 레코드에 로그인 ID·전화번호·주민번호·고용주·다른 지원자 정보 필드가 없다")
		void noPersonalInformationFields() {
			assertThat(componentNames(WorkerJobPostingResponse.class)).containsExactly("jobPostingId", "title", "description",
				"workLocation", "cropCode", "cropName", "workTypeCode", "workTypeName", "timeZone", "headcount", "dailyWage", "schedules");
			assertThat(componentNames(WorkerJobPostingResponse.Schedule.class)).containsExactly("workScheduleId", "workDate",
				"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt", "status", "termsVersion", "confirmedCount",
				"remainingSlots", "canApply", "unavailableReason");
			assertThat(componentNames(WorkerJobPostingsResponse.Posting.class)).containsExactly("jobPostingId", "title",
				"workLocation", "cropCode", "cropName", "workTypeCode", "workTypeName", "headcount", "dailyWage",
				"recruitingWorkDates", "recruitingSchedules");
		}

		private List<String> componentNames(Class<?> type) {
			return java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
		}
	}

	@Nested
	@DisplayName("6. 확정 인원과 남은 자리")
	class ConfirmedAndRemaining {
		@Test
		@DisplayName("집계를 일정 ID별로 연결하고, 여러 공고·날짜의 값이 섞이지 않으며, 누락은 0이다")
		void countsAreMappedById() {
			var a = posting(1, "GMT+9", 5);
			var b = posting(2, "GMT+9", 5);
			schedule(11, a, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(12, a, "2026-11-27", WorkSchedule.Status.OPEN);
			schedule(21, b, "2026-11-26", WorkSchedule.Status.OPEN);
			counts.put(11L, 1L);
			counts.put(21L, 4L);   // 12는 집계 행 누락

			var listed = service().list(WORKER_ID, 0, 20).jobPostings();

			assertThat(listed.get(0).recruitingSchedules()).extracting(WorkerJobPostingsResponse.RecruitingSchedule::workScheduleId,
				WorkerJobPostingsResponse.RecruitingSchedule::confirmedCount, WorkerJobPostingsResponse.RecruitingSchedule::remainingSlots)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(21L, 4L, 1L));
			assertThat(listed.get(1).recruitingSchedules()).extracting(WorkerJobPostingsResponse.RecruitingSchedule::workScheduleId,
				WorkerJobPostingsResponse.RecruitingSchedule::confirmedCount, WorkerJobPostingsResponse.RecruitingSchedule::remainingSlots)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(11L, 1L, 4L), org.assertj.core.groups.Tuple.tuple(12L, 0L, 5L));
		}

		@Test
		@DisplayName("remainingSlots = max(headcount - confirmedCount, 0): 미만·동일·초과·long 최대값")
		void remainingSlots() {
			var posting = posting(1, "GMT+9", 3);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-27", WorkSchedule.Status.OPEN);
			schedule(13, posting, "2026-11-28", WorkSchedule.Status.OPEN);
			schedule(14, posting, "2026-11-29", WorkSchedule.Status.OPEN);
			counts.put(11L, 2L);
			counts.put(12L, 3L);
			counts.put(13L, 7L);
			counts.put(14L, Long.MAX_VALUE);

			assertThat(service().detail(WORKER_ID, 1).schedules()).extracting(WorkerJobPostingResponse.Schedule::confirmedCount,
				WorkerJobPostingResponse.Schedule::remainingSlots, WorkerJobPostingResponse.Schedule::unavailableReason)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(2L, 1L, null),
					org.assertj.core.groups.Tuple.tuple(3L, 0L, "CAPACITY_FULL"),
					org.assertj.core.groups.Tuple.tuple(7L, 0L, "CAPACITY_FULL"),
					org.assertj.core.groups.Tuple.tuple(Long.MAX_VALUE, 0L, "CAPACITY_FULL"));
		}

		@Test
		@DisplayName("지원 대기(PENDING) 인원은 남은 자리를 줄이지 않는다")
		void pendingDoesNotReduceSlots() {
			var posting = posting(1, "GMT+9", 2);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			applications.put(11L, new Application(11L, "PENDING", null, null));

			var schedule = service().detail(WORKER_ID, 1).schedules().getFirst();

			assertThat(schedule.confirmedCount()).isZero();
			assertThat(schedule.remainingSlots()).isEqualTo(2);
		}

		@Test
		@DisplayName("정원이 차면 canApply=false·CAPACITY_FULL이며 OPEN 상태를 바꾸지 않고 목록에도 남는다")
		void fullCapacity() {
			var posting = posting(1, "GMT+9", 2);
			var target = schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			counts.put(11L, 2L);

			assertUnavailable(service().detail(WORKER_ID, 1).schedules().getFirst(), "CAPACITY_FULL");
			assertThat(service().list(WORKER_ID, 0, 20).jobPostings()).hasSize(1);
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
		}
	}

	@Nested
	@DisplayName("7. 본인 지원 이력과 지원 불가 사유")
	class OwnApplication {
		@Test
		@DisplayName("지원 이력·상태별 판정")
		void applicationStates() {
			var posting = posting(1, "GMT+9", 3);
			for (long id = 11; id <= 18; id++) {
				schedule(id, posting, LocalDate.parse("2026-11-26").plusDays(id - 11).toString(), WorkSchedule.Status.OPEN);
			}
			// 11: 지원 없음
			applications.put(12L, new Application(12L, "PENDING", null, null));
			applications.put(13L, new Application(13L, "SELECTED", null, null));        // 선정 표시만, 확정 근무 없음
			applications.put(14L, new Application(14L, "NOT_SELECTED", null, null));
			applications.put(15L, new Application(15L, "SELECTED", 901L, null));        // 확정, 변경 없음
			applications.put(16L, new Application(16L, "SELECTED", 902L, "EARLY_ENDED"));
			applications.put(17L, new Application(17L, "SELECTED", 903L, "CANCELLED"));
			applications.put(18L, new Application(18L, "WITHDRAWN", null, null));       // 알 수 없는 상태

			var detail = service().detail(WORKER_ID, 1).schedules();

			assertThat(detail).extracting(WorkerJobPostingResponse.Schedule::canApply, WorkerJobPostingResponse.Schedule::unavailableReason)
				.containsExactly(
					org.assertj.core.groups.Tuple.tuple(true, null),
					org.assertj.core.groups.Tuple.tuple(false, "ALREADY_APPLIED"),
					org.assertj.core.groups.Tuple.tuple(false, "ALREADY_CONFIRMED"),
					org.assertj.core.groups.Tuple.tuple(true, null),
					org.assertj.core.groups.Tuple.tuple(false, "ALREADY_CONFIRMED"),
					org.assertj.core.groups.Tuple.tuple(false, "ALREADY_CONFIRMED"),
					org.assertj.core.groups.Tuple.tuple(true, null),
					org.assertj.core.groups.Tuple.tuple(false, "APPLICATION_STATUS_UNAVAILABLE"));
		}

		@Test
		@DisplayName("목록과 상세는 같은 조건의 날짜를 같은 사유로 판정한다")
		void listAndDetailAgree() {
			var posting = posting(1, "GMT+9", 2);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-27", WorkSchedule.Status.OPEN);
			schedule(13, posting, "2026-11-28", WorkSchedule.Status.OPEN);
			counts.put(12L, 2L);
			applications.put(13L, new Application(13L, "PENDING", null, null));

			var listed = service().list(WORKER_ID, 0, 20).jobPostings().getFirst().recruitingSchedules();
			var detail = service().detail(WORKER_ID, 1).schedules();

			assertThat(listed).extracting(WorkerJobPostingsResponse.RecruitingSchedule::canApply,
				WorkerJobPostingsResponse.RecruitingSchedule::unavailableReason, WorkerJobPostingsResponse.RecruitingSchedule::remainingSlots)
				.containsExactlyElementsOf(detail.stream().map(schedule -> org.assertj.core.groups.Tuple.tuple(schedule.canApply(),
					schedule.unavailableReason(), schedule.remainingSlots())).toList());
		}

		@Test
		@DisplayName("본인 workerId와 대상 일정 ID들로 지원·집계를 조회한다")
		void queriesOwnApplicationsForTargetSchedules() {
			var posting = posting(1, "GMT+9", 2);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			schedule(12, posting, "2026-11-27", WorkSchedule.Status.CLOSED);

			service().detail(WORKER_ID, 1);
			service().list(WORKER_ID, 0, 20);

			verify(schedules).findWorkerApplications(WORKER_ID, List.of(11L, 12L));
			verify(schedules).countWorkerScheduleAssignments(List.of(11L, 12L));
			// 목록은 모집 대상 날짜(11)만 집계·지원 조회한다.
			verify(schedules).findWorkerApplications(WORKER_ID, List.of(11L));
			verify(schedules).countWorkerScheduleAssignments(List.of(11L));
		}

		@Test
		@DisplayName("사유 우선순위: 모집 상태 → 시간대·시작 → 정원 → 본인 지원")
		void reasonPriority() {
			var posting = posting(1, "GMT+9", 1);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.REMOVED);   // 취소 + 정원 충족
			schedule(12, posting, "2026-11-20", WorkSchedule.Status.OPEN);      // 시작 후 + 정원 충족
			schedule(13, posting, "2026-11-27", WorkSchedule.Status.OPEN);      // 정원 충족 + 본인 확정
			schedule(14, posting, "2026-11-28", WorkSchedule.Status.PAUSED);    // 중단 + 본인 지원
			schedule(15, posting, "2026-11-29", WorkSchedule.Status.CLOSED);
			for (long id = 11; id <= 13; id++) counts.put(id, 1L);
			applications.put(13L, new Application(13L, "SELECTED", 905L, null));
			applications.put(14L, new Application(14L, "PENDING", null, null));

			assertThat(service().detail(WORKER_ID, 1).schedules()).extracting(WorkerJobPostingResponse.Schedule::unavailableReason)
				.containsExactly("SCHEDULE_STARTED", "RECRUITMENT_CANCELLED", "CAPACITY_FULL", "RECRUITMENT_PAUSED",
					"RECRUITMENT_CLOSED");
		}

		@Test
		@DisplayName("시간대 오류는 정원·본인 지원보다 우선한다")
		void zoneBeforeCapacity() {
			var posting = posting(1, "Seoul", 1);
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);
			counts.put(11L, 1L);

			assertUnavailable(service().detail(WORKER_ID, 1).schedules().getFirst(), "TIME_ZONE_UNAVAILABLE");
		}
	}

	@Nested
	@DisplayName("8. 코드·이름 사전 연동")
	class CodeNames {
		@Test
		@DisplayName("목록·상세에서 코드 원문을 보존하고 사전 이름을 함께 반환한다")
		void namesAreAttached() {
			var posting = posting(1, "GMT+9", 3);
			codes(posting, "VC02100100100102", "0058");
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			var listed = service().list(WORKER_ID, 0, 20).jobPostings().getFirst();
			var detail = service().detail(WORKER_ID, 1);

			assertThat(List.of(listed.cropCode(), listed.cropName(), listed.workTypeCode(), listed.workTypeName()))
				.containsExactly("VC02100100100102", "노지가을배추", "0058", "수확");
			assertThat(List.of(detail.cropCode(), detail.cropName(), detail.workTypeCode(), detail.workTypeName()))
				.containsExactly("VC02100100100102", "노지가을배추", "0058", "수확");
		}

		@Test
		@DisplayName("미등록 기존 코드도 조회를 거부하지 않고 코드는 유지, 이름은 null이다")
		void unknownCodesAreKept() {
			var posting = posting(1, "GMT+9", 3);
			codes(posting, "LEGACY-CROP", "58");
			schedule(11, posting, "2026-11-26", WorkSchedule.Status.OPEN);

			var detail = service().detail(WORKER_ID, 1);
			var listed = service().list(WORKER_ID, 0, 20).jobPostings().getFirst();

			assertThat(detail.cropCode()).isEqualTo("LEGACY-CROP");
			assertThat(detail.cropName()).isNull();
			assertThat(detail.workTypeCode()).isEqualTo("58");
			assertThat(detail.workTypeName()).isNull();
			assertThat(listed.cropName()).isNull();
		}
	}

	private WorkerJobPostingQueryService service() {
		return service(Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private WorkerJobPostingQueryService service(Clock clock) {
		return new WorkerJobPostingQueryService(members, postings, schedules, CODES, clock);
	}

	private Member member(long id, MemberRole role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, role);
		ReflectionTestUtils.setField(member, "id", id);
		given(members.findById(id)).willReturn(Optional.of(member));
		return member;
	}

	// 공고마다 다른 고용주를 둔다. 정원은 headcount, 일급 120000.
	private JobPosting posting(long id, String zone, int headcount) {
		var employer = new Member("employer-" + id, "hash", "고용주 " + id, MemberRole.EMPLOYER);
		ReflectionTestUtils.setField(employer, "id", 1000 + id);
		var request = new CreateJobPostingRequest("공고 " + id, "설명 " + id, "VC02100100100102", "0058", "작업지 " + id, zone,
			headcount, 120000L, List.of());
		var posting = new JobPosting(employer, request);
		ReflectionTestUtils.setField(posting, "id", id);
		allPostings.add(posting);
		return posting;
	}

	private static void codes(JobPosting posting, String crop, String workType) {
		ReflectionTestUtils.setField(posting, "cropCode", crop);
		ReflectionTestUtils.setField(posting, "workTypeCode", workType);
	}

	// 현지 09:00~18:00 일정.
	private WorkSchedule schedule(long id, JobPosting posting, String date, WorkSchedule.Status status) {
		var schedule = new WorkSchedule(posting, new CreateJobPostingRequest.Schedule(LocalDate.parse(date),
			LocalDateTime.parse(date + "T09:00"), LocalDateTime.parse(date + "T18:00"), null, null));
		ReflectionTestUtils.setField(schedule, "id", id);
		ReflectionTestUtils.setField(schedule, "status", status);
		allSchedules.add(schedule);
		return schedule;
	}

	private static void assertApplicable(WorkerJobPostingResponse.Schedule schedule) {
		assertThat(schedule.canApply()).isTrue();
		assertThat(schedule.unavailableReason()).isNull();
	}

	private static void assertUnavailable(WorkerJobPostingResponse.Schedule schedule, String reason) {
		assertThat(schedule.canApply()).isFalse();
		assertThat(schedule.unavailableReason()).isEqualTo(reason);
	}

	private record Count(Long getWorkScheduleId, long getConfirmedCount) implements WorkScheduleRepository.OccupyingCount {}

	private record Application(Long getWorkScheduleId, String getApplicationStatus, Long getAssignmentId, String getChangeType)
		implements WorkScheduleRepository.WorkerApplication {}
}

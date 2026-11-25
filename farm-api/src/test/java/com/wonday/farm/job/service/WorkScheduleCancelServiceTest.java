package com.wonday.farm.job.service;

import static com.wonday.farm.job.testsupport.JobPostingFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.dto.CancelWorkScheduleRequest;
import com.wonday.farm.job.dto.CancelledWorkScheduleResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkScheduleCancelException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.testsupport.JobPostingFixtures;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// JOB-07 날짜 모집 취소 서비스. 저장소만 mock하고 공고·일정은 실제 도메인 객체, Clock은 고정값을 쓴다.
// mock 저장소는 SQL을 실행하지 않으므로 다음은 증명하지 않는다 (통합 검증 대상):
// findOwnedForUpdate의 소유자 필터·잠금, findByIdAndJobPosting_Id의 공고 연결 필터·잠금,
// countOccupyingAssignments의 CANCELLED 제외·EARLY_ENDED 포함 집계, JPA 변경 감지 저장, 커밋·롤백, 실제 행 보존.
// 서비스를 new로 만들었으므로 @Valid가 적용되지 않는다. DTO 검증은 CancelWorkScheduleRequestTest에서 본다.
class WorkScheduleCancelServiceTest {
	private static final long EMPLOYER_ID = 10L;
	private static final long POSTING_ID = 101L;
	private static final long SCHEDULE_ID = 201L;
	// 예정 시작: GMT+9 2026-12-02T09:00 = UTC 2026-12-02T00:00:00Z
	private static final Instant START = Instant.parse("2026-12-02T00:00:00Z");
	private static final Instant BEFORE = Instant.parse("2026-11-25T08:00:00.123456789Z");

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private Member employer;
	private JobPosting posting;
	private WorkSchedule target;

	@BeforeEach
	void ownedOpenScheduleExists() {
		employer = new Member("employer", "hash", "고용주", MemberRole.EMPLOYER);
		ReflectionTestUtils.setField(employer, "id", EMPLOYER_ID);
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(employer));
		posting = new JobPosting(employer, JobPostingFixtures.request("GMT+9", conditions()));
		ReflectionTestUtils.setField(posting, "id", POSTING_ID);
		target = new WorkSchedule(posting, conditions());
		ReflectionTestUtils.setField(target, "id", SCHEDULE_ID);
		given(postings.findOwnedForUpdate(POSTING_ID, EMPLOYER_ID)).willReturn(Optional.of(posting));
		given(schedules.findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID)).willReturn(Optional.of(target));
		given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(0L);
	}

	@Nested
	class SuccessfulCancel {
		@ParameterizedTest(name = "status={0}")
		@EnumSource(value = WorkSchedule.Status.class, names = {"OPEN", "PAUSED", "CLOSED"})
		void cancellableStatusBecomesRemoved(WorkSchedule.Status status) {
			ReflectionTestUtils.setField(target, "status", status);

			var response = cancelAt(BEFORE, 1);

			assertThat(response.status()).isEqualTo(WorkSchedule.Status.REMOVED);
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.REMOVED);
		}

		@Test
		void recordsServerTimeInUtcTruncatedToMicros() {
			cancelAt(BEFORE, 1);

			assertThat(target.getCancelledAt()).isEqualTo(LocalDateTime.parse("2026-11-25T08:00:00.123456"));
		}

		@Test
		void responseCarriesIdsDateStatusVersionAndUtcCancelTime() {
			var response = cancelAt(BEFORE, 1);

			assertThat(response).isEqualTo(new CancelledWorkScheduleResponse(POSTING_ID, SCHEDULE_ID,
				LocalDate.parse("2026-12-02"), WorkSchedule.Status.REMOVED, 1,
				OffsetDateTime.parse("2026-11-25T08:00:00.123456Z")));
			assertThat(response.cancelledAt().getOffset()).isEqualTo(ZoneOffset.UTC);
		}

		@Test
		void termsVersionIsNotIncremented() {
			ReflectionTestUtils.setField(target, "termsVersion", 4);

			assertThat(cancelAt(BEFORE, 4).termsVersion()).isEqualTo(4);
			assertThat(target.getTermsVersion()).isEqualTo(4);
		}

		@Test
		void maxIntVersionIsNotIncremented() {
			ReflectionTestUtils.setField(target, "termsVersion", Integer.MAX_VALUE);

			assertThat(cancelAt(BEFORE, Integer.MAX_VALUE).termsVersion()).isEqualTo(Integer.MAX_VALUE);
		}

		@Test
		void usesReadCommittedTransaction() throws Exception {
			var method = WorkScheduleCancelService.class.getMethod("cancel", long.class, long.class, long.class,
				CancelWorkScheduleRequest.class);

			assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
		}
	}

	@Nested
	class RoleOwnershipAndTarget {
		@Test
		void missingMemberIsDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());

			assertDeniedWithoutLookup();
		}

		@Test
		void workerIsDenied() {
			ReflectionTestUtils.setField(employer, "role", MemberRole.WORKER.name());

			assertDeniedWithoutLookup();
		}

		@Test
		void unsupportedRoleCodeIsDenied() {
			ReflectionTestUtils.setField(employer, "role", "ADMIN");

			assertDeniedWithoutLookup();
		}

		@Test
		void roleChangedToWorkerAfterLoginIsDenied() {
			// 세션은 EMPLOYER로 발급됐지만 현재 DB 역할이 WORKER로 바뀐 상황. 서비스는 매번 DB 역할을 다시 읽는다.
			// 같은 회원 객체로 한 번 성공한 뒤 역할만 바꿔, 두 번째 호출이 DB 역할을 다시 읽어 거부하는지 본다.
			var other = new WorkSchedule(posting, schedule("2026-12-03", "2026-12-03T09:00", "2026-12-03T18:00", null, null));
			ReflectionTestUtils.setField(other, "id", 202L);
			given(schedules.findByIdAndJobPosting_Id(202L, POSTING_ID)).willReturn(Optional.of(other));
			service(BEFORE).cancel(EMPLOYER_ID, POSTING_ID, 202L, request(1));
			ReflectionTestUtils.setField(employer, "role", MemberRole.WORKER.name());

			assertThatThrownBy(() -> cancelAt(BEFORE, 1)).isInstanceOf(AccessDeniedException.class);
			verify(schedules, never()).findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID);
			assertNotCancelled();
		}

		@ParameterizedTest(name = "postingId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositivePostingIdIsInvalidRequest(long postingId) {
			assertError(() -> service(BEFORE).cancel(EMPLOYER_ID, postingId, SCHEDULE_ID, request(1)),
				HttpStatus.BAD_REQUEST, "INVALID_REQUEST", null);
			verifyNoInteractions(postings, schedules);
		}

		@ParameterizedTest(name = "scheduleId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositiveScheduleIdIsInvalidRequest(long scheduleId) {
			assertError(() -> service(BEFORE).cancel(EMPLOYER_ID, POSTING_ID, scheduleId, request(1)),
				HttpStatus.BAD_REQUEST, "INVALID_REQUEST", null);
			verifyNoInteractions(postings, schedules);
		}

		@Test
		void callsRepositoriesWithCallerIdAndPathIdsInLockOrder() {
			cancelAt(BEFORE, 1);

			var order = inOrder(members, postings, schedules);
			order.verify(members).findById(EMPLOYER_ID);
			order.verify(postings).findOwnedForUpdate(POSTING_ID, EMPLOYER_ID);
			order.verify(schedules).findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID);
		}

		@Test
		void notOwnedPostingIs404AndScheduleIsNotRead() {
			given(postings.findOwnedForUpdate(POSTING_ID, EMPLOYER_ID)).willReturn(Optional.empty());

			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", null);
			verifyNoInteractions(schedules);
			assertNotCancelled();
		}

		@Test
		void scheduleNotInPostingIs404() {
			given(schedules.findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID)).willReturn(Optional.empty());

			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", null);
			assertNotCancelled();
		}

		private void assertDeniedWithoutLookup() {
			assertThatThrownBy(() -> cancelAt(BEFORE, 1)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(postings, schedules);
			assertNotCancelled();
		}
	}

	@Nested
	class PlannedStart {
		@Test
		void oneNanosecondBeforeStartIsAllowedAndCancelTimeIsTruncated() {
			var response = cancelAt(START.minusNanos(1), 1);

			assertThat(response.status()).isEqualTo(WorkSchedule.Status.REMOVED);
			assertThat(target.getCancelledAt()).isEqualTo(LocalDateTime.parse("2026-12-01T23:59:59.999999"));
		}

		@Test
		void exactlyAtStartIsAlreadyStarted() {
			assertStarted(START);
		}

		@Test
		void afterStartIsAlreadyStarted() {
			assertStarted(START.plusNanos(1));
			assertStarted(START.plusSeconds(3600));
		}

		@Test
		void gmtPlus9NineAmIsMidnightUtc() {
			// 현지 09:00을 UTC로 착각하면 UTC 08:59는 시작 전으로 판정된다. 실제로는 이미 시작(UTC 00:00) 이후다.
			assertStarted(Instant.parse("2026-12-02T08:59:00Z"));
			assertThat(cancelAt(Instant.parse("2026-12-01T23:59:00Z"), 1).status()).isEqualTo(WorkSchedule.Status.REMOVED);
		}

		@Test
		void usesTheStoredZoneOfThePosting() {
			ReflectionTestUtils.setField(posting, "timeZone", "UTC");

			// UTC 공고에서는 현지 09:00 = UTC 09:00이므로 UTC 08:59는 아직 시작 전이다.
			assertThat(cancelAt(Instant.parse("2026-12-02T08:59:00Z"), 1).status()).isEqualTo(WorkSchedule.Status.REMOVED);
			assertThat(posting.getTimeZone()).isEqualTo("UTC");
		}

		@Test
		void validExistingZoneIsNotOverwrittenWithGmtPlus9() {
			ReflectionTestUtils.setField(posting, "timeZone", "Asia/Seoul");

			cancelAt(BEFORE, 1);

			assertThat(posting.getTimeZone()).isEqualTo("Asia/Seoul");
		}

		@Test
		void startWithSubMicrosecondPartIsComparedAtNanosecondPrecision() {
			ReflectionTestUtils.setField(target, "plannedStartAt", LocalDateTime.parse("2026-12-02T09:00:00.000000500"));

			assertThat(cancelAt(Instant.parse("2026-12-02T00:00:00.000000499Z"), 1).status())
				.isEqualTo(WorkSchedule.Status.REMOVED);
			assertThat(target.getCancelledAt()).isEqualTo(LocalDateTime.parse("2026-12-02T00:00"));
		}

		@Test
		void startWithSubMicrosecondPartAtExactInstantIsStarted() {
			ReflectionTestUtils.setField(target, "plannedStartAt", LocalDateTime.parse("2026-12-02T09:00:00.000000500"));

			assertError(() -> cancelAt(Instant.parse("2026-12-02T00:00:00.000000500Z"), 1), HttpStatus.CONFLICT,
				"SCHEDULE_ALREADY_STARTED", null);
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(target.getCancelledAt()).isNull();
		}

		@Test
		void readsTheClockOnlyAfterLockingAndCountingConfirmedWork() {
			Clock clock = mock(Clock.class);
			given(clock.instant()).willReturn(BEFORE);

			new WorkScheduleCancelService(members, postings, schedules, clock)
				.cancel(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID, request(1));

			var order = inOrder(postings, schedules, clock);
			order.verify(postings).findOwnedForUpdate(POSTING_ID, EMPLOYER_ID);
			order.verify(schedules).findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID);
			order.verify(schedules).countOccupyingAssignments(SCHEDULE_ID);
			order.verify(clock).instant();
			verify(clock, times(1)).instant();
		}

		@ParameterizedTest(name = "timeZone=\"{0}\"")
		@NullSource
		@ValueSource(strings = {"", " ", "Seoul", "GMT+99", "Mars/Olympus"})
		void missingOrUnparsableZoneIsUnavailableAndNotFilledIn(String zone) {
			ReflectionTestUtils.setField(posting, "timeZone", zone);

			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.CONFLICT, "TIME_ZONE_UNAVAILABLE", null);
			assertThat(posting.getTimeZone()).as("GMT+9나 서버 기본 시간대로 보완하지 않는다").isEqualTo(zone);
			assertNotCancelled();
		}

		@Test
		void nonexistentLocalStartInDstZoneIsUnavailable() {
			ReflectionTestUtils.setField(posting, "timeZone", "America/New_York");
			ReflectionTestUtils.setField(target, "plannedStartAt", LocalDateTime.parse("2026-03-08T02:30"));

			assertError(() -> cancelAt(Instant.parse("2026-03-01T00:00:00Z"), 1), HttpStatus.CONFLICT,
				"SCHEDULE_START_TIME_UNAVAILABLE", null);
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(target.getCancelledAt()).isNull();
		}

		@Test
		void ambiguousLocalStartInDstZoneIsUnavailable() {
			ReflectionTestUtils.setField(posting, "timeZone", "America/New_York");
			ReflectionTestUtils.setField(target, "plannedStartAt", LocalDateTime.parse("2026-11-01T01:30"));

			assertError(() -> cancelAt(Instant.parse("2026-10-01T00:00:00Z"), 1), HttpStatus.CONFLICT,
				"SCHEDULE_START_TIME_UNAVAILABLE", null);
		}

		@Test
		void missingPlannedStartIsUnavailable() {
			ReflectionTestUtils.setField(target, "plannedStartAt", null);

			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.CONFLICT, "SCHEDULE_START_TIME_UNAVAILABLE", null);
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(target.getCancelledAt()).isNull();
		}

		private void assertStarted(Instant now) {
			assertError(() -> cancelAt(now, 1), HttpStatus.CONFLICT, "SCHEDULE_ALREADY_STARTED", null);
			assertNotCancelled();
		}
	}

	@Nested
	class ConfirmedWorkers {
		@Test
		void zeroOccupyingIsAllowed() {
			// 전원이 CANCELLED라면 SQL이 0을 반환한다는 것이 전제다. 그 SQL 자체는 검증하지 않는다.
			assertThat(cancelAt(BEFORE, 1).status()).isEqualTo(WorkSchedule.Status.REMOVED);
		}

		@ParameterizedTest(name = "occupying={0}")
		@ValueSource(longs = {1, 3, Long.MAX_VALUE})
		void positiveOccupyingIsAlreadyConfirmed(long occupying) {
			given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(occupying);

			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.CONFLICT, "SCHEDULE_ALREADY_CONFIRMED", null);
			assertNotCancelled();
		}

		@Test
		void countsOnlyTheTargetScheduleSoOtherDatesDoNotBlock() {
			given(schedules.countOccupyingAssignments(anyLong())).willReturn(7L);
			given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(0L);

			cancelAt(BEFORE, 1);

			verify(schedules, times(1)).countOccupyingAssignments(anyLong());
			verify(schedules).countOccupyingAssignments(SCHEDULE_ID);
		}
	}

	@Nested
	class TermsVersion {
		@ParameterizedTest(name = "expected={0}")
		@ValueSource(ints = {1, 3})
		void pastOrFutureVersionIsConflictAndNothingChanges(int expected) {
			ReflectionTestUtils.setField(target, "termsVersion", 2);

			assertError(() -> cancelAt(BEFORE, expected), HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT",
				"expectedTermsVersion");
			assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(target.getCancelledAt()).isNull();
			assertThat(target.getTermsVersion()).isEqualTo(2);
			verify(schedules, never()).countOccupyingAssignments(anyLong());
		}

		@Test
		void currentVersionCancels() {
			ReflectionTestUtils.setField(target, "termsVersion", 2);

			assertThat(cancelAt(BEFORE, 2).status()).isEqualTo(WorkSchedule.Status.REMOVED);
		}
	}

	@Nested
	class RepeatedRequest {
		@Test
		void sameRequestAgainReturnsTheFirstResult() {
			var first = cancelAt(BEFORE, 1);

			var again = cancelAt(BEFORE.plusSeconds(60), 1);

			assertThat(again).isEqualTo(first);
			assertThat(target.getCancelledAt()).isEqualTo(LocalDateTime.parse("2026-11-25T08:00:00.123456"));
		}

		@Test
		void repeatAfterPlannedStartStillReturnsTheExistingResult() {
			var first = cancelAt(BEFORE, 1);

			assertThat(cancelAt(START.plusSeconds(86400), 1)).isEqualTo(first);
		}

		@Test
		void repeatWithAnOlderPositiveVersionReturnsTheExistingResult() {
			removed(3, LocalDateTime.parse("2026-11-20T01:02:03.456789"));

			var response = cancelAt(BEFORE, 1);

			assertThat(response.termsVersion()).isEqualTo(3);
			assertThat(response.cancelledAt()).isEqualTo(OffsetDateTime.parse("2026-11-20T01:02:03.456789Z"));
		}

		@Test
		void existingRemovedRowWithoutCancelTimeIsNotFilledIn() {
			removed(1, null);

			var response = cancelAt(BEFORE, 1);

			assertThat(response.status()).isEqualTo(WorkSchedule.Status.REMOVED);
			assertThat(response.cancelledAt()).isNull();
			assertThat(target.getCancelledAt()).isNull();
		}

		@Test
		void repeatStillChecksRoleAndOwnership() {
			removed(1, LocalDateTime.parse("2026-11-20T00:00"));
			ReflectionTestUtils.setField(employer, "role", MemberRole.WORKER.name());
			assertThatThrownBy(() -> cancelAt(BEFORE, 1)).isInstanceOf(AccessDeniedException.class);

			ReflectionTestUtils.setField(employer, "role", MemberRole.EMPLOYER.name());
			given(postings.findOwnedForUpdate(POSTING_ID, EMPLOYER_ID)).willReturn(Optional.empty());
			assertError(() -> cancelAt(BEFORE, 1), HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", null);
		}

		@Test
		void repeatDoesNotRecountConfirmedWorkOrReadTheClockOrCheckTheZone() {
			removed(1, LocalDateTime.parse("2026-11-20T00:00"));
			ReflectionTestUtils.setField(posting, "timeZone", null);
			given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(5L);
			Clock clock = mock(Clock.class);

			var response = new WorkScheduleCancelService(members, postings, schedules, clock)
				.cancel(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID, request(1));

			assertThat(response.status()).isEqualTo(WorkSchedule.Status.REMOVED);
			verify(schedules, never()).countOccupyingAssignments(anyLong());
			verifyNoInteractions(clock);
		}

		private void removed(int version, LocalDateTime cancelledAt) {
			ReflectionTestUtils.setField(target, "status", WorkSchedule.Status.REMOVED);
			ReflectionTestUtils.setField(target, "termsVersion", version);
			ReflectionTestUtils.setField(target, "cancelledAt", cancelledAt);
		}
	}

	@Nested
	class Preservation {
		@Test
		void keepsScheduleIdentityConditionsAndPostingCommonTerms() {
			var before = conditionsOf(target);
			var postingBefore = List.of(posting.getHeadcount(), posting.getDailyWage(), posting.getWorkLocation(),
				posting.getCropCode(), posting.getWorkTypeCode(), posting.getTimeZone());

			cancelAt(BEFORE, 1);

			assertThat(target.getId()).isEqualTo(SCHEDULE_ID);
			assertThat(target.getJobPosting()).isSameAs(posting);
			assertThat(conditionsOf(target)).isEqualTo(before);
			assertThat(target.getTermsVersion()).isEqualTo(1);
			assertThat(List.of(posting.getHeadcount(), posting.getDailyWage(), posting.getWorkLocation(),
				posting.getCropCode(), posting.getWorkTypeCode(), posting.getTimeZone())).isEqualTo(postingBefore);
		}

		@Test
		void otherScheduleOfTheSamePostingIsUntouched() {
			var other = new WorkSchedule(posting, schedule("2026-12-03", "2026-12-03T09:00", "2026-12-03T18:00", null, null));
			ReflectionTestUtils.setField(other, "id", 202L);

			cancelAt(BEFORE, 1);

			assertThat(other.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
			assertThat(other.getCancelledAt()).isNull();
			verify(schedules, never()).findByIdAndJobPosting_Id(202L, POSTING_ID);
		}

		@Test
		void onlyReadsFromRepositoriesAndNeverSavesOrDeletes() {
			// 저장은 JPA 변경 감지에 맡기는 설계다. 저장소에 대한 호출이 조회·집계뿐인지 확인한다.
			cancelAt(BEFORE, 1);

			verify(postings).findOwnedForUpdate(POSTING_ID, EMPLOYER_ID);
			verify(schedules).findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID);
			verify(schedules).countOccupyingAssignments(SCHEDULE_ID);
			verifyNoMoreInteractions(postings, schedules);
		}

		private List<Object> conditionsOf(WorkSchedule value) {
			return List.of(value.getWorkDate(), value.getPlannedStartAt(), value.getPlannedEndAt(),
				value.getBreakStartAt(), value.getBreakEndAt());
		}
	}

	private CancelledWorkScheduleResponse cancelAt(Instant now, int version) {
		return service(now).cancel(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID, request(version));
	}

	private WorkScheduleCancelService service(Instant now) {
		return new WorkScheduleCancelService(members, postings, schedules, Clock.fixed(now, ZoneOffset.UTC));
	}

	private void assertNotCancelled() {
		assertThat(target.getStatus()).isEqualTo(WorkSchedule.Status.OPEN);
		assertThat(target.getCancelledAt()).isNull();
		assertThat(target.getTermsVersion()).isEqualTo(1);
		assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-02"));
		assertThat(target.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
	}

	private static CancelWorkScheduleRequest request(int version) {
		return new CancelWorkScheduleRequest(version);
	}

	private static com.wonday.farm.job.dto.CreateJobPostingRequest.Schedule conditions() {
		return schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00", "2026-12-02T13:00");
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkScheduleCancelException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.field()).isEqualTo(field);
		});
	}
}

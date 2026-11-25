package com.wonday.farm.job.service;

import static com.wonday.farm.job.testsupport.JobPostingFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.UpdateWorkScheduleRequest;
import com.wonday.farm.job.dto.UpdatedWorkScheduleResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.exception.WorkScheduleUpdateException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// JOB-06 일정 수정 서비스. 저장소만 mock하고 공고·일정·이력은 실제 도메인 객체를 쓴다.
// mock 저장소는 실제 SQL을 실행하지 않으므로 다음은 증명하지 않는다 (통합 검증 대상):
// findOwnedForUpdate의 소유자 필터·비관적 잠금, findByIdAndJobPosting_Id의 공고 연결 필터,
// countOccupyingAssignments의 CANCELLED/EARLY_ENDED 집계, 중복 날짜 조회, 유니크 제약, 트랜잭션 롤백.
// 서비스를 new로 만들었으므로 @Valid/@Validated도 적용되지 않는다. DTO 필수값은 UpdateWorkScheduleRequestTest에서 본다.
class WorkScheduleUpdateServiceTest {
	private static final long EMPLOYER_ID = 10L;
	private static final long POSTING_ID = 1L;
	private static final long SCHEDULE_ID = 11L;
	private static final Instant NOW = Instant.parse("2026-11-25T03:04:05.123456789Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-25T03:04:05.123456");

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkScheduleRevisionRepository revisions = mock(WorkScheduleRevisionRepository.class);
	private final WorkScheduleUpdateService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
	private Member employer;
	private JobPosting posting;
	private WorkSchedule target;

	@BeforeEach
	void ownedScheduleExists() {
		employer = member(EMPLOYER_ID, MemberRole.EMPLOYER);
		posting = new JobPosting(employer, JobPostingFixtures.request("GMT+9",
			schedule("2026-12-01", "2026-12-01T08:00", "2026-12-01T17:00", "2026-12-01T12:00", "2026-12-01T13:00")));
		ReflectionTestUtils.setField(posting, "id", POSTING_ID);
		target = new WorkSchedule(posting,
			schedule("2026-12-01", "2026-12-01T08:00", "2026-12-01T17:00", "2026-12-01T12:00", "2026-12-01T13:00"));
		ReflectionTestUtils.setField(target, "id", SCHEDULE_ID);
		given(postings.findOwnedForUpdate(POSTING_ID, EMPLOYER_ID)).willReturn(Optional.of(posting));
		given(schedules.findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID)).willReturn(Optional.of(target));
		given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(0L);
		given(revisions.save(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	@Nested
	class SuccessfulUpdate {
		@Test
		void changesEveryConditionAndReturnsTheResult() {
			var response = update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00",
				"2026-12-02T12:30", "2026-12-02T13:30"));

			assertThat(response).isEqualTo(new UpdatedWorkScheduleResponse(POSTING_ID, SCHEDULE_ID,
				LocalDate.parse("2026-12-02"), LocalDateTime.parse("2026-12-02T09:00"), LocalDateTime.parse("2026-12-02T18:00"),
				LocalDateTime.parse("2026-12-02T12:30"), LocalDateTime.parse("2026-12-02T13:30"), WorkSchedule.Status.OPEN, 2));
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-02"));
			assertThat(target.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(target.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T18:00"));
			assertThat(target.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T12:30"));
			assertThat(target.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T13:30"));
		}

		@Test
		void eachFieldCanChangeOnItsOwn() {
			update(request(1, "2026-12-01", "2026-12-01T08:00", "2026-12-01T16:00", "2026-12-01T12:00", "2026-12-01T13:00"));
			assertThat(target.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T16:00"));

			update(request(2, "2026-12-01", "2026-12-01T07:00", "2026-12-01T16:00", "2026-12-01T12:00", "2026-12-01T13:00"));
			assertThat(target.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T07:00"));

			update(request(3, "2026-12-01", "2026-12-01T07:00", "2026-12-01T16:00", "2026-12-01T11:00", "2026-12-01T13:00"));
			assertThat(target.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T11:00"));

			update(request(4, "2026-12-01", "2026-12-01T07:00", "2026-12-01T16:00", "2026-12-01T11:00", "2026-12-01T12:00"));
			assertThat(target.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:00"));

			update(request(5, "2026-12-05", "2026-12-05T07:00", "2026-12-05T16:00", "2026-12-05T11:00", "2026-12-05T12:00"));
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-05"));
			assertThat(target.getTermsVersion()).isEqualTo(6);
		}

		@Test
		void breakCanBeRemovedAndAddedAgain() {
			update(request(1, "2026-12-01", "2026-12-01T08:00", "2026-12-01T17:00", null, null));
			assertThat(target.getBreakStartAt()).isNull();
			assertThat(target.getBreakEndAt()).isNull();

			update(request(2, "2026-12-01", "2026-12-01T08:00", "2026-12-01T17:00", "2026-12-01T12:00", "2026-12-01T12:30"));
			assertThat(target.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:00"));
			assertThat(target.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:30"));
		}

		@Test
		void overnightShiftEndingNextDayIsAccepted() {
			var response = update(request(1, "2026-12-02", "2026-12-02T22:00", "2026-12-03T06:00",
				"2026-12-03T01:00", "2026-12-03T02:00"));

			assertThat(response.workDate()).isEqualTo(LocalDate.parse("2026-12-02"));
			assertThat(response.plannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-03T06:00"));
		}

		@Test
		void incrementsTermsVersionByExactlyOne() {
			ReflectionTestUtils.setField(target, "termsVersion", 7);

			assertThat(update(request(7, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null)).termsVersion())
				.isEqualTo(8);
			assertThat(target.getTermsVersion()).isEqualTo(8);
		}

		@Test
		void keepsScheduleIdPostingLinkAndPostingCommonTerms() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			assertThat(target.getId()).isEqualTo(SCHEDULE_ID);
			assertThat(target.getJobPosting()).isSameAs(posting);
			assertThat(posting.getHeadcount()).isEqualTo(2);
			assertThat(posting.getDailyWage()).isEqualTo(120000L);
			assertThat(posting.getWorkLocation()).isEqualTo("충청북도 충주시 예시농장");
			assertThat(posting.getCropCode()).isEqualTo(JobPostingFixtures.request("GMT+9").cropCode());
			assertThat(posting.getWorkTypeCode()).isEqualTo(JobPostingFixtures.request("GMT+9").workTypeCode());
			assertThat(posting.getTimeZone()).isEqualTo("GMT+9");
		}

		@Test
		void usesReadCommittedTransaction() throws Exception {
			var method = WorkScheduleUpdateService.class.getMethod("update", long.class, long.class, long.class,
				UpdateWorkScheduleRequest.class);

			assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
		}
	}

	@Nested
	class RoleOwnershipAndTarget {
		@Test
		void missingMemberIsAccessDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());

			assertDeniedWithoutLookup();
		}

		@Test
		void workerIsAccessDenied() {
			member(EMPLOYER_ID, MemberRole.WORKER);

			assertDeniedWithoutLookup();
		}

		@Test
		void roleChangedToWorkerAfterLoginIsAccessDenied() {
			// 세션 발급 당시 EMPLOYER였더라도 현재 DB 역할을 다시 확인한다.
			ReflectionTestUtils.setField(employer, "role", MemberRole.WORKER.name());

			assertDeniedWithoutLookup();
		}

		@Test
		void unsupportedRoleCodeIsAccessDenied() {
			ReflectionTestUtils.setField(employer, "role", "ADMIN");

			assertDeniedWithoutLookup();
		}

		@ParameterizedTest(name = "postingId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositivePostingIdIsInvalidRequest(long postingId) {
			assertJobPostingError(() -> service.update(EMPLOYER_ID, postingId, SCHEDULE_ID, unchangedRequest()),
				"INVALID_REQUEST", null);
			verifyNoInteractions(postings, schedules, revisions);
		}

		@ParameterizedTest(name = "scheduleId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositiveScheduleIdIsInvalidRequest(long scheduleId) {
			assertJobPostingError(() -> service.update(EMPLOYER_ID, POSTING_ID, scheduleId, unchangedRequest()),
				"INVALID_REQUEST", null);
			verifyNoInteractions(postings, schedules, revisions);
		}

		@Test
		void looksUpPostingWithCallerIdAndScheduleWithThatPostingId() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			var order = inOrder(members, postings, schedules);
			order.verify(members).findById(EMPLOYER_ID);
			order.verify(postings).findOwnedForUpdate(POSTING_ID, EMPLOYER_ID);
			order.verify(schedules).findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID);
		}

		@Test
		void notOwnedOrMissingPostingIs404AndScheduleIsNotRead() {
			given(postings.findOwnedForUpdate(POSTING_ID, EMPLOYER_ID)).willReturn(Optional.empty());

			assertUpdateError(() -> update(changedRequest()), HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", null);
			verifyNoInteractions(schedules, revisions);
			assertUnchanged();
		}

		@Test
		void scheduleOfAnotherPostingIs404() {
			given(schedules.findByIdAndJobPosting_Id(SCHEDULE_ID, POSTING_ID)).willReturn(Optional.empty());

			assertUpdateError(() -> update(changedRequest()), HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", null);
			verifyNoInteractions(revisions);
		}

		private void assertDeniedWithoutLookup() {
			assertThatThrownBy(() -> update(changedRequest())).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(postings, schedules, revisions);
			assertUnchanged();
		}
	}

	@Nested
	class EditableStatusAndConfirmedWorkers {
		@ParameterizedTest(name = "status={0}")
		@EnumSource(value = WorkSchedule.Status.class, names = {"OPEN", "PAUSED", "CLOSED"})
		void editableStatusIsKeptAfterUpdate(WorkSchedule.Status status) {
			ReflectionTestUtils.setField(target, "status", status);

			var response = update(changedRequest());

			assertThat(response.status()).isEqualTo(status);
			assertThat(target.getStatus()).as("수정만으로 모집을 재개하지 않는다").isEqualTo(status);
			assertThat(target.getTermsVersion()).isEqualTo(2);
		}

		@Test
		void removedScheduleIsNotEditable() {
			ReflectionTestUtils.setField(target, "status", WorkSchedule.Status.REMOVED);

			assertUpdateError(() -> update(changedRequest()), HttpStatus.CONFLICT, "SCHEDULE_NOT_EDITABLE", null);
			assertUnchanged();
			verify(revisions, never()).save(any());
		}

		@ParameterizedTest(name = "occupying={0}")
		@ValueSource(longs = {1, 2, Long.MAX_VALUE})
		void scheduleWithOccupyingAssignmentsIsNotEditable(long occupying) {
			given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(occupying);

			assertUpdateError(() -> update(changedRequest()), HttpStatus.CONFLICT, "SCHEDULE_ALREADY_CONFIRMED", null);
			assertUnchanged();
			verify(revisions, never()).save(any());
		}

		@Test
		void zeroOccupyingAssignmentsIsEditable() {
			// 모든 확정자가 시작 전에 취소한 경우 SQL이 0을 반환한다는 것이 전제다. 그 SQL 자체는 검증하지 않는다.
			assertThat(update(changedRequest()).termsVersion()).isEqualTo(2);
		}

		@Test
		void countsOnlyTheTargetSchedule() {
			given(schedules.countOccupyingAssignments(anyLong())).willReturn(5L);
			given(schedules.countOccupyingAssignments(SCHEDULE_ID)).willReturn(0L);

			update(changedRequest());

			verify(schedules).countOccupyingAssignments(SCHEDULE_ID);
			verify(schedules, times(1)).countOccupyingAssignments(anyLong());
		}
	}

	@Nested
	class DateWorkAndBreakValidation {
		@Test
		void duplicateDateInSamePostingIs400OnWorkDate() {
			given(schedules.existsByJobPosting_IdAndWorkDateAndIdNot(POSTING_ID, LocalDate.parse("2026-12-02"), SCHEDULE_ID))
				.willReturn(true);

			assertJobPostingError(() -> update(changedRequest()), "DUPLICATE_WORK_DATE", "workDate");
			assertUnchanged();
		}

		@Test
		void duplicateLookupExcludesTheScheduleItself() {
			update(changedRequest());

			verify(schedules).existsByJobPosting_IdAndWorkDateAndIdNot(POSTING_ID, LocalDate.parse("2026-12-02"), SCHEDULE_ID);
		}

		@Test
		void keepingTheSameDateIsNotADuplicate() {
			update(request(1, "2026-12-01", "2026-12-01T09:00", "2026-12-01T17:00", null, null));

			verify(schedules).existsByJobPosting_IdAndWorkDateAndIdNot(POSTING_ID, LocalDate.parse("2026-12-01"), SCHEDULE_ID);
			assertThat(target.getTermsVersion()).isEqualTo(2);
		}

		@Test
		void workDateDifferentFromStartDateIsInvalidWorkTimeOnWorkDate() {
			assertInvalid(request(1, "2026-12-03", "2026-12-02T09:00", "2026-12-02T18:00", null, null),
				"INVALID_WORK_TIME", "workDate");
		}

		@ParameterizedTest(name = "end={0}")
		@ValueSource(strings = {"2026-12-02T09:00", "2026-12-02T08:59:59.999999"})
		void endNotAfterStartIsInvalidWorkTimeOnPlannedEndAt(String end) {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", end, null, null), "INVALID_WORK_TIME", "plannedEndAt");
		}

		@Test
		void missingBreakStartIsInvalidOnBreakStartAt() {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, "2026-12-02T13:00"),
				"INVALID_BREAK_TIME", "breakStartAt");
		}

		@Test
		void missingBreakEndIsInvalidOnBreakEndAt() {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00", null),
				"INVALID_BREAK_TIME", "breakEndAt");
		}

		@ParameterizedTest(name = "breakEnd={0}")
		@ValueSource(strings = {"2026-12-02T12:00", "2026-12-02T11:00"})
		void breakEndNotAfterBreakStartIsInvalidOnBreakEndAt(String breakEnd) {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00", breakEnd),
				"INVALID_BREAK_TIME", "breakEndAt");
		}

		@Test
		void breakStartingBeforeWorkIsInvalidOnBreakStartAt() {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T08:59", "2026-12-02T10:00"),
				"INVALID_BREAK_TIME", "breakStartAt");
		}

		@Test
		void breakEndingAfterWorkIsInvalidOnBreakEndAt() {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T17:00", "2026-12-02T18:01"),
				"INVALID_BREAK_TIME", "breakEndAt");
		}

		@Test
		void breakMayCoverTheWholeShift() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T09:00", "2026-12-02T18:00"));

			assertThat(target.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(target.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T18:00"));
		}

		@Test
		void microsecondPrecisionIsPreserved() {
			update(request(1, "2026-12-02", "2026-12-02T09:00:00.000001", "2026-12-02T18:00:00.999999", null, null));

			assertThat(target.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00:00.000001"));
			assertThat(target.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T18:00:00.999999"));
		}

		@Test
		void subMicrosecondPrecisionIsInvalidLocalTime() {
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00:00.0000001", "2026-12-02T18:00", null, null),
				"INVALID_LOCAL_TIME", "plannedStartAt");
			assertInvalid(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00",
				"2026-12-02T13:00:00.000000001"), "INVALID_LOCAL_TIME", "breakEndAt");
		}

		@Test
		void supportedYearRangeIs1000Through9999() {
			update(request(1, "1000-01-01", "1000-01-01T00:00", "1000-01-01T01:00", null, null));
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("1000-01-01"));
			update(request(2, "9999-12-31", "9999-12-31T22:00", "9999-12-31T23:59:59.999999", null, null));
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("9999-12-31"));
		}

		@Test
		void yearsOutsideTheSupportedRangeAreInvalidLocalTime() {
			assertInvalid(request(1, "0999-12-31", "0999-12-31T09:00", "0999-12-31T18:00", null, null),
				"INVALID_LOCAL_TIME", "plannedStartAt");
			assertInvalid(request(1, "9999-12-31", "9999-12-31T09:00", "+10000-01-01T01:00", null, null),
				"INVALID_LOCAL_TIME", "plannedEndAt");
		}

		@ParameterizedTest(name = "timeZone=\"{0}\"")
		@NullSource
		@ValueSource(strings = {"", " ", "Seoul", "GMT+99"})
		void missingOrUnparsableStoredTimeZoneIsConflictAndNotFilledIn(String zone) {
			ReflectionTestUtils.setField(posting, "timeZone", zone);

			assertUpdateError(() -> update(changedRequest()), HttpStatus.CONFLICT, "TIME_ZONE_UNAVAILABLE", null);
			assertThat(posting.getTimeZone()).as("GMT+9로 임의 보완하지 않는다").isEqualTo(zone);
			assertUnchanged();
		}

		@ParameterizedTest(name = "timeZone={0}")
		@ValueSource(strings = {"Asia/Seoul", "GMT+09:00", "UTC"})
		void otherValidStoredTimeZonesAreStillAccepted(String zone) {
			// 신규 등록은 GMT+9만 허용하지만, 수정은 이미 저장된 유효한 시간대를 거부하지 않는다.
			ReflectionTestUtils.setField(posting, "timeZone", zone);

			assertThat(update(changedRequest()).termsVersion()).isEqualTo(2);
			assertThat(posting.getTimeZone()).isEqualTo(zone);
		}

		@Test
		void validationFailureSavesNoHistory() {
			assertInvalid(request(1, "2026-12-03", "2026-12-02T09:00", "2026-12-02T18:00", null, null),
				"INVALID_WORK_TIME", "workDate");
			verify(revisions, never()).save(any());
			verify(revisions, never()).existsByWorkSchedule_IdAndTermsVersion(anyLong(), anyInt());
		}

		private void assertInvalid(UpdateWorkScheduleRequest request, String code, String field) {
			assertJobPostingError(() -> update(request), code, field);
			assertUnchanged();
		}
	}

	@Nested
	class TermsVersion {
		@ParameterizedTest(name = "expected={0}")
		@ValueSource(ints = {1, 3})
		void pastOrFutureVersionIsConflictOnExpectedTermsVersion(int expected) {
			ReflectionTestUtils.setField(target, "termsVersion", 2);

			assertUpdateError(() -> update(request(expected, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null)),
				HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT", "expectedTermsVersion");
			assertThat(target.getTermsVersion()).isEqualTo(2);
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-01"));
			verifyNoInteractions(revisions);
		}

		@Test
		void sameConditionsWithCurrentVersionSucceedWithoutNewVersionOrHistory() {
			var response = update(unchangedRequest());

			assertThat(response.termsVersion()).isEqualTo(1);
			assertThat(target.getTermsVersion()).isEqualTo(1);
			verifyNoInteractions(revisions);
		}

		@Test
		void sameConditionsWithStaleVersionIsStillAConflict() {
			ReflectionTestUtils.setField(target, "termsVersion", 2);

			assertUpdateError(() -> update(unchangedRequest()), HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT",
				"expectedTermsVersion");
		}

		@Test
		void realChangeAtMaxVersionIsExhausted() {
			ReflectionTestUtils.setField(target, "termsVersion", Integer.MAX_VALUE);

			assertUpdateError(() -> update(request(Integer.MAX_VALUE, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00",
				null, null)), HttpStatus.CONFLICT, "SCHEDULE_VERSION_EXHAUSTED", null);
			assertThat(target.getTermsVersion()).isEqualTo(Integer.MAX_VALUE);
			assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-01"));
			verifyNoInteractions(revisions);
		}

		@Test
		void sameConditionsAtMaxVersionReturnNormally() {
			ReflectionTestUtils.setField(target, "termsVersion", Integer.MAX_VALUE);

			var response = service.update(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID, new UpdateWorkScheduleRequest(Integer.MAX_VALUE,
				LocalDate.parse("2026-12-01"), LocalDateTime.parse("2026-12-01T08:00"), LocalDateTime.parse("2026-12-01T17:00"),
				LocalDateTime.parse("2026-12-01T12:00"), LocalDateTime.parse("2026-12-01T13:00")));

			assertThat(response.termsVersion()).isEqualTo(Integer.MAX_VALUE);
			verifyNoInteractions(revisions);
		}

		@Test
		void secondEditorWithStaleVersionIsRejectedThenSucceedsWithLatestVersion() {
			// 순차 재현: A·B가 버전 1을 읽음 → B가 수정해 버전 2 → A의 버전 1 요청 거부 → A가 버전 2로 재요청.
			// 오래된 버전을 거부하는 로직만 검증한다. 실제 동시 트랜잭션·잠금 경합은 검증하지 않는다.
			int readByA = target.getTermsVersion();
			int readByB = target.getTermsVersion();

			update(request(readByB, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));
			assertUpdateError(() -> update(request(readByA, "2026-12-03", "2026-12-03T09:00", "2026-12-03T18:00", null, null)),
				HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT", "expectedTermsVersion");
			assertThat(target.getWorkDate()).as("A의 거부된 요청은 B의 결과를 덮어쓰지 않는다").isEqualTo(LocalDate.parse("2026-12-02"));

			var retried = update(request(target.getTermsVersion(), "2026-12-03", "2026-12-03T09:00", "2026-12-03T18:00", null, null));

			assertThat(retried.termsVersion()).isEqualTo(3);
			assertThat(retried.workDate()).isEqualTo(LocalDate.parse("2026-12-03"));
		}
	}

	@Nested
	class History {
		@Test
		void firstRealUpdateSavesBaselineThenUpdated() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			List<WorkScheduleRevision> saved = savedRevisions(2);
			var baseline = saved.get(0);
			var updated = saved.get(1);
			assertThat(baseline.getRevisionType()).isEqualTo(WorkScheduleRevision.Type.BASELINE);
			assertThat(baseline.getTermsVersion()).isEqualTo(1);
			assertThat(baseline.getActorId()).isNull();
			assertThat(baseline.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-01"));
			assertThat(baseline.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T08:00"));
			assertThat(baseline.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T17:00"));
			assertThat(baseline.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:00"));
			assertThat(baseline.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T13:00"));
			assertThat(updated.getRevisionType()).isEqualTo(WorkScheduleRevision.Type.UPDATED);
			assertThat(updated.getTermsVersion()).isEqualTo(2);
			assertThat(updated.getActorId()).isEqualTo(EMPLOYER_ID);
			assertThat(updated.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-02"));
			assertThat(updated.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(updated.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T18:00"));
			assertThat(updated.getBreakStartAt()).isNull();
			assertThat(updated.getBreakEndAt()).isNull();
		}

		@Test
		void checksBaselineExistenceForTheCurrentVersionBeforeChanging() {
			ReflectionTestUtils.setField(target, "termsVersion", 4);

			update(request(4, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			verify(revisions).existsByWorkSchedule_IdAndTermsVersion(SCHEDULE_ID, 4);
		}

		@Test
		void existingRevisionForCurrentVersionMeansOnlyUpdatedIsAdded() {
			given(revisions.existsByWorkSchedule_IdAndTermsVersion(SCHEDULE_ID, 1)).willReturn(true);

			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			assertThat(savedRevisions(1)).extracting(WorkScheduleRevision::getRevisionType)
				.containsExactly(WorkScheduleRevision.Type.UPDATED);
		}

		@Test
		void consecutiveUpdatesAddOneBaselineAndOneUpdatedPerChange() {
			given(revisions.existsByWorkSchedule_IdAndTermsVersion(SCHEDULE_ID, 2)).willReturn(true);

			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));
			update(request(2, "2026-12-03", "2026-12-03T09:00", "2026-12-03T18:00", null, null));

			assertThat(savedRevisions(3)).extracting(WorkScheduleRevision::getRevisionType, WorkScheduleRevision::getTermsVersion)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(WorkScheduleRevision.Type.BASELINE, 1),
					org.assertj.core.groups.Tuple.tuple(WorkScheduleRevision.Type.UPDATED, 2),
					org.assertj.core.groups.Tuple.tuple(WorkScheduleRevision.Type.UPDATED, 3));
		}

		@Test
		void snapshotCopiesScheduleLinkAndPostingCommonTerms() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			for (var revision : savedRevisions(2)) {
				assertThat(revision.getWorkSchedule()).isSameAs(target);
				assertThat(revision.getTimeZone()).isEqualTo("GMT+9");
				assertThat(revision.getWorkLocation()).isEqualTo("충청북도 충주시 예시농장");
				assertThat(revision.getCropCode()).isEqualTo(posting.getCropCode());
				assertThat(revision.getWorkTypeCode()).isEqualTo(posting.getWorkTypeCode());
				assertThat(revision.getHeadcount()).isEqualTo(2);
				assertThat(revision.getDailyWage()).isEqualTo(120000L);
			}
		}

		@Test
		void baselineAndUpdatedShareTheObservedUtcTimeTruncatedToMicros() {
			// BASELINE 시각은 과거 생성 시각을 추측하지 않고 이번 수정에서 관측한 시각이다.
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			assertThat(savedRevisions(2)).extracting(WorkScheduleRevision::getRecordedAtUtc).containsOnly(NOW_UTC);
		}

		@Test
		void recordedTimeFollowsTheClock() {
			var later = service(Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), ZoneOffset.UTC));

			later.update(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID,
				request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));

			assertThat(savedRevisions(2)).extracting(WorkScheduleRevision::getRecordedAtUtc)
				.containsOnly(LocalDateTime.parse("2026-12-31T15:00"));
		}

		@Test
		void earlierSnapshotsDoNotChangeWhenTheScheduleChangesAgain() {
			update(request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null));
			var first = savedRevisions(2);

			update(request(2, "2026-12-05", "2026-12-05T06:00", "2026-12-05T15:00", "2026-12-05T10:00", "2026-12-05T11:00"));

			assertThat(first.get(0).getWorkDate()).isEqualTo(LocalDate.parse("2026-12-01"));
			assertThat(first.get(0).getTermsVersion()).isEqualTo(1);
			assertThat(first.get(1).getWorkDate()).isEqualTo(LocalDate.parse("2026-12-02"));
			assertThat(first.get(1).getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(first.get(1).getBreakStartAt()).isNull();
			assertThat(first.get(1).getTermsVersion()).isEqualTo(2);
		}

		@Test
		void baselineSaveFailureStopsBeforeChangingTheSchedule() {
			given(revisions.save(any())).willThrow(new DataIntegrityViolationException("duplicate baseline"));

			assertThatThrownBy(() -> update(changedRequest())).isInstanceOf(DataIntegrityViolationException.class);
			assertUnchanged();
			verify(revisions, times(1)).save(any());
		}

		@Test
		void updatedSaveFailureIsNotReportedAsSuccess() {
			// 두 번째 저장(UPDATED)만 실패시킨다. 메모리의 일정 객체는 이미 바뀐 상태이며,
			// DB 롤백으로 되돌아가는지는 통합 검증 대상이다. 여기서는 예외가 그대로 전파되는지만 본다.
			given(revisions.save(any()))
				.willAnswer(invocation -> invocation.getArgument(0))
				.willThrow(new DataIntegrityViolationException("duplicate updated"));

			assertThatThrownBy(() -> update(changedRequest())).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	private UpdatedWorkScheduleResponse update(UpdateWorkScheduleRequest request) {
		return service.update(EMPLOYER_ID, POSTING_ID, SCHEDULE_ID, request);
	}

	private WorkScheduleUpdateService service(Clock clock) {
		return new WorkScheduleUpdateService(members, postings, schedules, revisions, clock);
	}

	private List<WorkScheduleRevision> savedRevisions(int expectedCount) {
		var captor = ArgumentCaptor.forClass(WorkScheduleRevision.class);
		verify(revisions, times(expectedCount)).save(captor.capture());
		return captor.getAllValues();
	}

	private void assertUnchanged() {
		assertThat(target.getTermsVersion()).isEqualTo(1);
		assertThat(target.getWorkDate()).isEqualTo(LocalDate.parse("2026-12-01"));
		assertThat(target.getPlannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T08:00"));
		assertThat(target.getPlannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T17:00"));
		assertThat(target.getBreakStartAt()).isEqualTo(LocalDateTime.parse("2026-12-01T12:00"));
		assertThat(target.getBreakEndAt()).isEqualTo(LocalDateTime.parse("2026-12-01T13:00"));
	}

	private Member member(long id, MemberRole role) {
		var member = new Member("employer-" + id, "hash", "고용주", role);
		ReflectionTestUtils.setField(member, "id", id);
		given(members.findById(id)).willReturn(Optional.of(member));
		return member;
	}

	private static UpdateWorkScheduleRequest unchangedRequest() {
		return request(1, "2026-12-01", "2026-12-01T08:00", "2026-12-01T17:00", "2026-12-01T12:00", "2026-12-01T13:00");
	}

	private static UpdateWorkScheduleRequest changedRequest() {
		return request(1, "2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null);
	}

	private static UpdateWorkScheduleRequest request(int version, String date, String start, String end, String breakStart,
			String breakEnd) {
		CreateJobPostingRequest.Schedule conditions = schedule(date, start, end, breakStart, breakEnd);
		return new UpdateWorkScheduleRequest(version, conditions.workDate(), conditions.plannedStartAt(),
			conditions.plannedEndAt(), conditions.breakStartAt(), conditions.breakEndAt());
	}

	private static void assertUpdateError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkScheduleUpdateException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.field()).isEqualTo(field);
		});
	}

	private static void assertJobPostingError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code,
			String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(JobPostingException.class, error -> {
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.field()).isEqualTo(field);
		});
	}
}

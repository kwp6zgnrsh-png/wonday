package com.wonday.farm.change.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.insurance.entity.ChangeStream;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import com.wonday.farm.work.testsupport.SelectionFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// WorkChangeRecorder.cancelBeforeStart의 실제 코드. Repository는 mock, Clock은 테스트가 제어한다.
// 호출 순서와 저장 인자는 확인하지만 실제 MySQL 행 잠금·잠금 대기·커밋 순서·롤백·유니크 제약은 증명하지 않는다.
@DisplayName("CHANGE-01·02 시작 전 취소 저장 경계 (WorkChangeRecorder.cancelBeforeStart)")
class WorkChangeRecorderCancellationTest {
	private static final long EMPLOYER = 10L;
	private static final long WORKER = 5L;
	private static final long ASSIGNMENT = 31L;
	private static final long CHANGE_ID = 77L;
	// 기본 약정: 2026-11-30 08:00 GMT+9 = 2026-11-29T23:00:00Z
	private static final Instant START = Instant.parse("2026-11-29T23:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-28T06:00:00.123456789Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-28T06:00:00.123456");

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final MutableClock clock = new MutableClock(NOW);
	private final WorkChangeRecorder recorder = new WorkChangeRecorder(members, assignments, changes, streams, versions, clock);
	private ChangeStream stream;
	private WorkAssignment stored;

	static final class MutableClock extends Clock {
		Instant now;
		int reads;
		MutableClock(Instant now) { this.now = now; }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { reads++; return now; }
	}

	@BeforeEach
	void owned() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.of(ownership(WORKER, EMPLOYER)));
		stream = stream(WORKER, 4);
		given(streams.lockWorker(WORKER)).willReturn(Optional.of(stream));
		stored = assignment(ASSIGNMENT);
		given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.of(stored));
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
		given(changes.saveAndFlush(any())).willAnswer(invocation -> {
			WorkChange saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", CHANGE_ID);
			return saved;
		});
	}

	private WorkChangeRecorder.RecordedCancellation cancel() {
		return recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.RAIN, "비");
	}
	private static void expect(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkCancellationException.class, e -> {
			assertThat(e.status()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}
	private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE");
		});
	}
	private void nothingWritten() {
		verify(changes, never()).saveAndFlush(any());
		verify(changes, never()).save(any());
		verifyNoInteractions(versions);
		assertThat(stream.getLastSequence()).isEqualTo(4);
	}
	private void noLocks() {
		verifyNoInteractions(streams, versions);
		verify(assignments, never()).findByIdForUpdate(anyLong());
		verify(changes, never()).findForUpdate(anyLong());
		verify(changes, never()).saveAndFlush(any());
	}
	private static List<Object> snapshot(WorkChange c) {
		return List.of(c.getId(), c.getAssignmentId(), c.getActorId(), c.getChangeType(), c.getReasonCode(),
			String.valueOf(c.getWeatherReasonCode()), String.valueOf(c.getReasonDetail()), c.getEffectiveAt(), c.getRecordedAt(),
			c.getUpdatedAt(), c.getSourceVersion());
	}
	private WorkChange existing(Type type, Reason reason, WeatherReason weather, String detail) {
		var change = change(500L, ASSIGNMENT, type, reason, weather, detail,
			LocalDateTime.parse("2026-11-27T01:00:00.000001"), LocalDateTime.parse("2026-11-27T01:00:00.000002"));
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(change));
		return change;
	}

	@Nested
	@DisplayName("권한·소유권")
	class Authority {
		@Test
		void missingMemberIsDeniedBeforeLookupAndLocks() {
			given(members.findById(EMPLOYER)).willReturn(Optional.empty());
			assertThatThrownBy(() -> cancel()).isInstanceOf(AccessDeniedException.class);
			verify(assignments, never()).findOwnership(anyLong());
			noLocks();
		}

		@Test
		void workerIsDenied() {
			given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.WORKER)));
			assertThatThrownBy(() -> cancel()).isInstanceOf(AccessDeniedException.class);
			verify(assignments, never()).findOwnership(anyLong());
			noLocks();
		}

		@ParameterizedTest
		@ValueSource(strings = {"ADMIN", "INSURER", "employer", ""})
		void unknownRoleIsDenied(String code) {
			given(members.findById(EMPLOYER)).willReturn(Optional.of(memberWithRoleCode(EMPLOYER, code)));
			assertThatThrownBy(() -> cancel()).isInstanceOf(AccessDeniedException.class);
			noLocks();
		}

		@Test
		void roleIsReadFromCurrentDbMember() {
			cancel();
			verify(members).findById(EMPLOYER);
		}

		@Test
		void otherEmployersAssignmentAndMissingAssignmentAreTheSame404() {
			given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.of(ownership(WORKER, 99L)));
			var other = catchCancel();
			given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.empty());
			var missing = catchCancel();
			for (var e : List.of(other, missing)) {
				assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.code()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND");
			}
			assertThat(other.getMessage()).isEqualTo(missing.getMessage());
			noLocks();
		}

		@Test
		void invalidIdsAndReasonsFailBeforeAnyLookup() {
			expect(() -> recorder.cancelBeforeStart(0, ASSIGNMENT, Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, -1, Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, WeatherReason.WIND, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, null, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, "가".repeat(501)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, assignments, changes, streams, versions);
		}

		private WorkCancellationException catchCancel() {
			try { cancel(); } catch (WorkCancellationException e) { return e; }
			throw new AssertionError("expected 404");
		}
	}

	@Nested
	@DisplayName("시작 시각 판정")
	class Start {
		@Test
		void justBeforeAgreedStartIsAllowedAndStoredTruncatedToMicros() {
			clock.now = START.minusNanos(1);
			var result = cancel();
			assertThat(result.change().getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:59:59.999999"));
		}

		@Test
		void oneMicroBeforeIsAllowed() {
			clock.now = START.minusNanos(1000);
			assertThat(cancel().change().getChangeType()).isEqualTo(Type.CANCELLED);
		}

		@ParameterizedTest
		@ValueSource(longs = {0, 1, 1000, 60_000_000_000L, 86_400_000_000_000L})
		void exactlyAtOrAfterStartIs409AndNothingIsWritten(long nanosAfter) {
			clock.now = START.plusNanos(nanosAfter);
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_STARTED");
			nothingWritten();
		}

		@Test
		void usesAgreedTimeZoneNotGmt9Assumption() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "UTC");
			// UTC 약정 시작 = 2026-11-30T08:00Z. GMT+9로 해석했다면 이미 시작(23:00Z 전날)이다.
			clock.now = Instant.parse("2026-11-30T07:59:59Z");
			assertThat(cancel().timeZone()).isEqualTo("UTC");
			clock.now = Instant.parse("2026-11-30T08:00:00Z");
			given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_STARTED");
		}

		@Test
		void usesAgreementCopyNotTheCurrentlyEditedSchedule() {
			var posting = SelectionFixtures.posting(1);
			var schedule = SelectionFixtures.schedule(posting, WorkSchedule.Status.OPEN, 1);
			var agreed = SelectionFixtures.assignment(ASSIGNMENT, schedule, LocalDateTime.parse("2026-11-20T00:00"));
			given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.of(agreed));
			// 약정 후 공고 일정이 과거로 당겨져도(현재 공고 기준이면 이미 시작) 약정 시각 2026-12-10T00:00Z를 기준으로 한다.
			ReflectionTestUtils.setField(schedule, "plannedStartAt", LocalDateTime.parse("2026-11-01T09:00"));
			clock.now = Instant.parse("2026-12-09T23:59:59.999999Z");
			assertThat(cancel().change().getChangeType()).isEqualTo(Type.CANCELLED);
			// 반대로 일정이 늦춰져도 약정 시작이 지났으면 거절한다.
			ReflectionTestUtils.setField(schedule, "plannedStartAt", LocalDateTime.parse("2026-12-31T09:00"));
			given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			clock.now = Instant.parse("2026-12-10T00:00:00Z");
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_STARTED");
		}

		@Test
		void clockIsReadAfterAllLocksSoLockWaitCounts() {
			// 잠금 대기 동안 시각이 시작 시각을 넘긴 상황: lockWorker 응답 시점에 시계를 진행시킨다.
			clock.now = START.minusSeconds(1);
			given(streams.lockWorker(WORKER)).willAnswer(invocation -> { clock.now = START.plusMillis(5); return Optional.of(stream); });
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_STARTED");
			nothingWritten();
		}

		@Test
		void clockReadComesAfterChangeRowLock() {
			var spyClock = mock(Clock.class);
			given(spyClock.instant()).willReturn(NOW);
			var ordered = new WorkChangeRecorder(members, assignments, changes, streams, versions, spyClock);
			ordered.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, null);
			var order = inOrder(streams, assignments, changes, spyClock, versions);
			order.verify(streams).ensureExists(WORKER);
			order.verify(streams).lockWorker(WORKER);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
			order.verify(changes).findForUpdate(ASSIGNMENT);
			order.verify(spyClock).instant();
			order.verify(changes).saveAndFlush(any());
			order.verify(versions).save(any());
			verify(spyClock, times(1)).instant();
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "Mars/Olympus", "GMT+99"})
		void unusableTimeZoneIs503(String zone) {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", zone);
			unavailable(() -> cancel());
			nothingWritten();
		}

		@Test
		void nullTimeZoneOrStartIs503() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", null);
			unavailable(() -> cancel());
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "GMT+9");
			ReflectionTestUtils.setField(stored, "agreedStartAt", null);
			unavailable(() -> cancel());
			nothingWritten();
		}

		@Test
		void daylightGapAndOverlapStartIs503() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "America/New_York");
			ReflectionTestUtils.setField(stored, "agreedStartAt", LocalDateTime.parse("2027-03-14T02:30"));
			unavailable(() -> cancel());
			ReflectionTestUtils.setField(stored, "agreedStartAt", LocalDateTime.parse("2027-11-07T01:30"));
			unavailable(() -> cancel());
			nothingWritten();
		}

		@ParameterizedTest
		@EnumSource(WorkSchedule.Status.class)
		void recruitmentStatusDoesNotBlockIndividualCancellation(WorkSchedule.Status status) {
			var schedule = SelectionFixtures.schedule(SelectionFixtures.posting(1), status, 1);
			var agreed = SelectionFixtures.assignment(ASSIGNMENT, schedule, LocalDateTime.parse("2026-11-20T00:00"));
			given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.of(agreed));
			assertThat(cancel().change().getChangeType()).as(status.name()).isEqualTo(Type.CANCELLED);
		}
	}

	@Nested
	@DisplayName("완료·조기 종료 충돌")
	class States {
		@Test
		void completedAtIs409() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-27T09:00"));
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			nothingWritten();
		}

		@Test
		void completedByAloneIs409() {
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER);
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			nothingWritten();
		}

		@Test
		void earlyEndedIs409AndRecordUnchanged() {
			var early = existing(Type.EARLY_ENDED, Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(early);
			expect(() -> cancel(), HttpStatus.CONFLICT, "WORK_ALREADY_EARLY_ENDED");
			assertThat(snapshot(early)).isEqualTo(before);
			nothingWritten();
		}
	}

	@Nested
	@DisplayName("동일 재요청·다른 내용 충돌")
	class Replay {
		@Test
		void sameContentReturnsTheSameRecordWithoutWriting() {
			var previous = existing(Type.CANCELLED, Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(previous);
			var result = cancel();
			assertThat(result.change()).isSameAs(previous);
			assertThat(result.timeZone()).isEqualTo("GMT+9");
			assertThat(snapshot(previous)).isEqualTo(before);
			nothingWritten();
		}

		@Test
		void replayAfterStartReturnsExistingAndDoesNotReadClock() {
			var previous = existing(Type.CANCELLED, Reason.OTHER, null, null);
			clock.now = START.plusSeconds(86_400 * 3);
			clock.reads = 0;
			assertThat(recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, null).change()).isSameAs(previous);
			assertThat(clock.reads).isZero();
			nothingWritten();
		}

		@Test
		void detailComparedAfterNormalization() {
			var withText = existing(Type.CANCELLED, Reason.FARM_CIRCUMSTANCES, null, "농장 사정");
			for (var detail : new String[] {"농장 사정", "  농장 사정\n", "\t농장 사정　"}) {
				assertThat(recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.FARM_CIRCUMSTANCES, null, detail).change())
					.as("[%s]", detail).isSameAs(withText);
			}
			var empty = existing(Type.CANCELLED, Reason.OTHER, null, null);
			for (var detail : new String[] {null, "", "   ", "\n\t"}) {
				assertThat(recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, detail).change()).isSameAs(empty);
			}
			// 저장 원본 쪽에 앞뒤 공백이 남아 있어도(기존 경로 저장분) 정규화 후 비교한다.
			var padded = existing(Type.CANCELLED, Reason.OTHER, null, "  기타  ");
			assertThat(recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, "기타").change()).isSameAs(padded);
			nothingWritten();
		}

		@Test
		void eachSingleDifferenceIs409AndRecordIsUnchanged() {
			var previous = existing(Type.CANCELLED, Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(previous);
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, "비"), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.WIND, "비"), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.RAIN, "비 옴"), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.RAIN, null), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
			assertThat(snapshot(previous)).isEqualTo(before);
			nothingWritten();
		}

		@Test
		void nullDetailVersusTextIs409() {
			existing(Type.CANCELLED, Reason.OTHER, null, null);
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.OTHER, null, "추가"), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
			nothingWritten();
		}

		@Test
		void conflictCheckedEvenAfterStart() {
			existing(Type.CANCELLED, Reason.OTHER, null, null);
			clock.now = START.plusSeconds(1);
			expect(() -> recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.FARM_CIRCUMSTANCES, null, null), HttpStatus.CONFLICT, "CANCELLATION_REASON_CONFLICT");
		}

		@Test
		void previousRoundCancellationOnAnotherAssignmentIsNotTouched() {
			long oldRound = 30L;
			var old = change(400L, oldRound, Type.CANCELLED, Reason.OTHER, null, "이전 회차", NOW_UTC.minusDays(3), NOW_UTC.minusDays(3));
			var before = snapshot(old);
			given(changes.findForUpdate(oldRound)).willReturn(Optional.of(old));
			var result = cancel();
			assertThat(result.change().getAssignmentId()).isEqualTo(ASSIGNMENT);
			assertThat(result.change().getId()).isEqualTo(CHANGE_ID);
			verify(changes, never()).findForUpdate(oldRound);
			assertThat(snapshot(old)).isEqualTo(before);
		}
	}

	@Nested
	@DisplayName("최초 저장과 보험 조회용 버전")
	class Store {
		@Test
		void savesNormalizedCancelledSourceWithSessionEmployerAndServerTime() {
			var result = recorder.cancelBeforeStart(EMPLOYER, ASSIGNMENT, Reason.WEATHER, WeatherReason.HEAT, " \n 폭염 경보\n주의 \t");
			var captor = ArgumentCaptor.forClass(WorkChange.class);
			verify(changes).saveAndFlush(captor.capture());
			var saved = captor.getValue();
			assertThat(result.change()).isSameAs(saved);
			assertThat(saved.getId()).isEqualTo(CHANGE_ID);
			assertThat(saved.getAssignmentId()).isEqualTo(ASSIGNMENT);
			assertThat(saved.getActorId()).isEqualTo(EMPLOYER);
			assertThat(saved.getChangeType()).isEqualTo(Type.CANCELLED);
			assertThat(saved.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(saved.getWeatherReasonCode()).isEqualTo(WeatherReason.HEAT);
			assertThat(saved.getReasonDetail()).isEqualTo("폭염 경보\n주의");
			assertThat(saved.getEffectiveAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getRecordedAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getUpdatedAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getSourceVersion()).isEqualTo(1);
		}

		@Test
		void savesMatchingVersionWithNextSequence() {
			cancel();
			var captor = ArgumentCaptor.forClass(WorkChangeVersion.class);
			verify(versions).save(captor.capture());
			var v = captor.getValue();
			assertThat(stream.getLastSequence()).isEqualTo(5);
			assertThat(v.getSequenceNo()).isEqualTo(5);
			assertThat(v.getWorkerId()).isEqualTo(WORKER);
			assertThat(v.getAssignmentId()).isEqualTo(ASSIGNMENT);
			assertThat(v.getChangeId()).isEqualTo(CHANGE_ID);
			assertThat(v.getSourceVersion()).isEqualTo(1);
			assertThat(v.getChangeType()).isEqualTo(Type.CANCELLED);
			assertThat(v.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(v.getWeatherReasonCode()).isEqualTo(WeatherReason.RAIN);
			assertThat(v.getReasonDetail()).isEqualTo("비");
			assertThat(v.getEventTimeZone()).isEqualTo("GMT+9");
			assertThat(v.getEffectiveAt()).isEqualTo(NOW_UTC);
			assertThat(v.getRecordedAt()).isEqualTo(NOW_UTC);
			assertThat(v.getUpdatedAt()).isEqualTo(NOW_UTC);
			verify(versions, times(1)).save(any());
		}

		@Test
		void lockOrderIsStreamThenAssignmentThenChange() {
			cancel();
			var order = inOrder(members, assignments, streams, changes, versions);
			order.verify(members).findById(EMPLOYER);
			order.verify(assignments).findOwnership(ASSIGNMENT);
			order.verify(streams).ensureExists(WORKER);
			order.verify(streams).lockWorker(WORKER);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT);
			order.verify(changes).findForUpdate(ASSIGNMENT);
			order.verify(changes).saveAndFlush(any());
			order.verify(versions).save(any());
		}

		@Test
		void versionSaveFailurePropagates() {
			willThrow(new DataIntegrityViolationException("dup")).given(versions).save(any());
			assertThatThrownBy(() -> cancel()).isInstanceOf(DataIntegrityViolationException.class);
		}

		@Test
		void missingStreamLockIs503AndNothingWritten() {
			given(streams.lockWorker(WORKER)).willReturn(Optional.empty());
			expect(() -> cancel(), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			nothingWritten();
		}

		@Test
		void assignmentVanishedUnderLockIs404() {
			given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			expect(() -> cancel(), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			nothingWritten();
		}

		@Test
		void doesNotTouchAgreementApplicationOrOtherAssignments() {
			var agreed = List.of(stored.getApplicationId(), stored.getAgreedStartAt(), stored.getAgreedEndAt(), stored.getAgreedTimeZone(),
				stored.getAgreedWorkDate(), stored.getAgreedDailyWage(), String.valueOf(stored.getCompletedAtUtc()));
			cancel();
			assertThat(List.of(stored.getApplicationId(), stored.getAgreedStartAt(), stored.getAgreedEndAt(), stored.getAgreedTimeZone(),
				stored.getAgreedWorkDate(), stored.getAgreedDailyWage(), String.valueOf(stored.getCompletedAtUtc()))).isEqualTo(agreed);
			// 저장소 호출은 이 근무 ID의 조회·잠금과 변경 INSERT 한 번뿐이다. 삭제·다른 근무 호출 없음.
			verify(assignments).findOwnership(ASSIGNMENT);
			verify(assignments).findByIdForUpdate(ASSIGNMENT);
			verifyNoMoreInteractions(assignments);
			verify(changes).findForUpdate(ASSIGNMENT);
			verify(changes).saveAndFlush(any());
			verifyNoMoreInteractions(changes);
			verify(streams).ensureExists(WORKER);
			verify(streams).lockWorker(WORKER);
			verifyNoMoreInteractions(streams);
		}
	}

	private static WorkAssignmentRepository.Ownership ownership(long workerId, long employerId) {
		return new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return workerId; }
			public long getEmployerId() { return employerId; }
		};
	}
}

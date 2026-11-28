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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkEarlyEndException;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// WorkChangeRecorder.endWork의 실제 코드. Repository는 mock, Clock은 테스트가 제어한다.
// 호출 순서와 저장 인자는 확인하지만 실제 MySQL 행 잠금·잠금 대기·커밋 순서·롤백·유니크 제약은 증명하지 않는다.
@DisplayName("CHANGE-03 조기 종료 저장 경계 (WorkChangeRecorder.endWork)")
class WorkChangeRecorderEarlyEndTest {
	private static final long EMPLOYER = 10L, WORKER = 5L, ASSIGNMENT = 31L, CHANGE_ID = 77L;
	// 기본 약정: 2026-11-30 08:00~17:00 GMT+9 = 2026-11-29T23:00Z ~ 2026-11-30T08:00Z, 입력 기한 2026-12-01T08:00Z
	private static final Instant START = Instant.parse("2026-11-29T23:00:00Z");
	private static final Instant END = Instant.parse("2026-11-30T08:00:00Z");
	private static final Instant DEADLINE = Instant.parse("2026-12-01T08:00:00Z");
	private static final Instant NOW = Instant.parse("2026-11-30T09:00:00.123456789Z");
	private static final OffsetDateTime ACTUAL = OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00");
	private static final LocalDateTime ACTUAL_UTC = LocalDateTime.parse("2026-11-30T05:00:00.123456");

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

	private WorkChangeRecorder.RecordedEarlyEnd end(OffsetDateTime actual) {
		return recorder.endWork(EMPLOYER, ASSIGNMENT, actual, Reason.WEATHER, WeatherReason.RAIN, "비");
	}
	private WorkChangeRecorder.RecordedEarlyEnd end() { return end(ACTUAL); }
	private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.ofHours(9)); }
	private static void expect(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> {
			assertThat(e.status()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}
	private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).satisfies(e -> {
			HttpStatus status = e instanceof WorkEarlyEndException w ? w.status() : ((InsuranceApiException) e).status();
			String code = e instanceof WorkEarlyEndException w ? w.code() : ((InsuranceApiException) e).code();
			assertThat(status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(code).isEqualTo("SERVICE_UNAVAILABLE");
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
	private WorkChange existing(Type type, LocalDateTime effective, Reason reason, WeatherReason weather, String detail) {
		var change = change(500L, ASSIGNMENT, type, reason, weather, detail, effective, LocalDateTime.parse("2026-11-30T09:30:00.000002"));
		ReflectionTestUtils.setField(change, "updatedAt", LocalDateTime.parse("2026-11-30T09:40:00.000003"));
		ReflectionTestUtils.setField(change, "sourceVersion", 2L);
		given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.of(change));
		return change;
	}

	@Nested
	@DisplayName("권한·소유권")
	class Authority {
		@Test
		void missingWorkerUnknownRoleDenied() {
			for (var member : List.of(Optional.<com.wonday.farm.member.Member>empty(), Optional.of(member(EMPLOYER, MemberRole.WORKER)),
					Optional.of(memberWithRoleCode(EMPLOYER, "ADMIN")), Optional.of(memberWithRoleCode(EMPLOYER, "")))) {
				given(members.findById(EMPLOYER)).willReturn(member);
				assertThatThrownBy(() -> end()).isInstanceOf(AccessDeniedException.class);
			}
			verify(assignments, never()).findOwnership(anyLong());
			noLocks();
		}

		@Test
		void otherOwnerAndMissingAreTheSame404() {
			given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.of(ownership(WORKER, 99L)));
			var other = catchEnd();
			given(assignments.findOwnership(ASSIGNMENT)).willReturn(Optional.empty());
			var missing = catchEnd();
			for (var e : List.of(other, missing)) {
				assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(e.code()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND");
			}
			assertThat(other.getMessage()).isEqualTo(missing.getMessage());
			noLocks();
		}

		@Test
		void invalidInputFailsBeforeLookup() {
			expect(() -> recorder.endWork(0, ASSIGNMENT, ACTUAL, Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, 0, ACTUAL, Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, null, Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL.plusNanos(1), Reason.OTHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.WEATHER, null, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, WeatherReason.HEAT, null), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, null, "가".repeat(501)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, assignments, changes, streams, versions);
		}

		private WorkEarlyEndException catchEnd() {
			try { end(); } catch (WorkEarlyEndException e) { return e; }
			throw new AssertionError("expected 404");
		}
	}

	@Nested
	@DisplayName("신규 기록 상태")
	class States {
		@Test
		void cancelledIs409() {
			var cancelled = existing(Type.CANCELLED, LocalDateTime.parse("2026-11-29T01:00"), Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(cancelled);
			expect(() -> end(), HttpStatus.CONFLICT, "WORK_ALREADY_CANCELLED");
			assertThat(snapshot(cancelled)).isEqualTo(before);
			nothingWritten();
		}

		@Test
		void completedIs409EvenWithOnlyOneCompletionField() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-30T08:30"));
			expect(() -> end(), HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			ReflectionTestUtils.setField(stored, "completedAtUtc", null);
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER);
			expect(() -> end(), HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			verify(changes, never()).findForUpdate(anyLong());
			nothingWritten();
		}

		@ParameterizedTest
		@EnumSource(WorkSchedule.Status.class)
		void recruitmentStatusDoesNotBlock(WorkSchedule.Status status) {
			// SelectionFixtures 약정: 2026-12-10 09:00~17:00 GMT+9 = 00:00Z~08:00Z
			var schedule = SelectionFixtures.schedule(SelectionFixtures.posting(1), status, 1);
			given(assignments.findByIdForUpdate(ASSIGNMENT))
				.willReturn(Optional.of(SelectionFixtures.assignment(ASSIGNMENT, schedule, LocalDateTime.parse("2026-12-01T00:00"))));
			clock.now = Instant.parse("2026-12-10T09:00:00Z");
			assertThat(end(OffsetDateTime.parse("2026-12-10T12:00+09:00")).change().getChangeType()).as(status.name()).isEqualTo(Type.EARLY_ENDED);
		}
	}

	@Nested
	@DisplayName("시작·실제 종료 범위")
	class Times {
		@Test
		void beforeStartIs409NotStarted() {
			clock.now = START.minusNanos(1);
			expect(() -> end(at(START)), HttpStatus.CONFLICT, "WORK_NOT_STARTED");
			nothingWritten();
		}

		@Test
		void exactlyAtStartAllowedWithActualEqualStartAndNow() {
			clock.now = START;
			var result = end(at(START));
			assertThat(result.change().getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-29T23:00"));
		}

		@Test
		void actualEndRange() {
			// 시작과 같음 허용, 종료 1µs 전 허용
			assertThat(end(at(START)).change().getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-29T23:00"));
			given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			assertThat(end(at(END.minusNanos(1000))).change().getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-30T07:59:59.999999"));
			// 시작 1µs 전, 종료와 같음, 종료 이후는 400
			for (var bad : List.of(START.minusNanos(1000), END, END.plusNanos(1000), END.plusSeconds(3600))) {
				expect(() -> end(at(bad)), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			}
		}

		@Test
		void actualEqualToNowAllowedFutureRejectedWithoutTruncatingNow() {
			// 근무 중 입력: 서버 시각 05:00:00.123456789Z
			clock.now = Instant.parse("2026-11-30T05:00:00.123456789Z");
			assertThat(end(OffsetDateTime.parse("2026-11-30T05:00:00.123456Z")).change().getEffectiveAt())
				.isEqualTo(LocalDateTime.parse("2026-11-30T05:00:00.123456"));
			given(changes.findForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			expect(() -> end(OffsetDateTime.parse("2026-11-30T05:00:00.123457Z")), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			// 서버 시각과 정확히 같은 마이크로초 값
			clock.now = Instant.parse("2026-11-30T05:00:00.123456Z");
			assertThat(end(OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00")).change().getChangeType()).isEqualTo(Type.EARLY_ENDED);
		}

		@Test
		void lateEntryAfterAgreedEndCanRecordEarlierActualEnd() {
			clock.now = END.plusSeconds(3600);
			assertThat(end(OffsetDateTime.parse("2026-11-30T14:00+09:00")).change().getEffectiveAt())
				.isEqualTo(LocalDateTime.parse("2026-11-30T05:00"));
		}

		@Test
		void usesAgreementNotCurrentSchedule() {
			var schedule = SelectionFixtures.schedule(SelectionFixtures.posting(1), WorkSchedule.Status.OPEN, 1);
			var agreed = SelectionFixtures.assignment(ASSIGNMENT, schedule, LocalDateTime.parse("2026-12-01T00:00"));
			given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.of(agreed));
			ReflectionTestUtils.setField(schedule, "plannedStartAt", LocalDateTime.parse("2026-12-10T13:00"));
			ReflectionTestUtils.setField(schedule, "plannedEndAt", LocalDateTime.parse("2026-12-10T14:00"));
			clock.now = Instant.parse("2026-12-10T07:00:00Z");
			// 약정 09:00~17:00 기준 10:00 허용 (현재 일정 13:00~14:00이면 범위 밖)
			assertThat(end(OffsetDateTime.parse("2026-12-10T10:00+09:00")).change().getChangeType()).isEqualTo(Type.EARLY_ENDED);
		}

		@Test
		void usesAgreedTimeZone() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "UTC");
			// UTC 약정 08:00~17:00Z. GMT+9 가정이면 08:00Z는 이미 종료 후다.
			clock.now = Instant.parse("2026-11-30T09:00:00Z");
			assertThat(end(OffsetDateTime.parse("2026-11-30T08:30:00Z")).timeZone()).isEqualTo("UTC");
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "Mars/Olympus", "GMT+99"})
		void unusableZoneIs503(String zone) {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", zone);
			unavailable(() -> end());
			nothingWritten();
		}

		@Test
		void missingAgreementTimesIs503() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", null);
			unavailable(() -> end());
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "GMT+9");
			ReflectionTestUtils.setField(stored, "agreedStartAt", null);
			unavailable(() -> end());
			ReflectionTestUtils.setField(stored, "agreedStartAt", LocalDateTime.parse("2026-11-30T08:00"));
			ReflectionTestUtils.setField(stored, "agreedEndAt", null);
			unavailable(() -> end());
			nothingWritten();
		}

		@Test
		void endNotAfterStartIs503() {
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-11-30T08:00"));
			unavailable(() -> end());
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-11-30T07:00"));
			unavailable(() -> end());
			nothingWritten();
		}

		@Test
		void daylightGapOrOverlapIs503() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "America/New_York");
			ReflectionTestUtils.setField(stored, "agreedStartAt", LocalDateTime.parse("2027-03-14T02:30"));
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2027-03-14T10:00"));
			unavailable(() -> end());
			ReflectionTestUtils.setField(stored, "agreedStartAt", LocalDateTime.parse("2027-11-07T00:00"));
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2027-11-07T01:30"));
			unavailable(() -> end());
			nothingWritten();
		}
	}

	@Nested
	@DisplayName("최초 입력 기한")
	class Deadline {
		@Test
		void exactlyDeadlineAllowed() {
			clock.now = DEADLINE;
			assertThat(end().change().getRecordedAt()).isEqualTo(LocalDateTime.parse("2026-12-01T08:00"));
		}

		@Test
		void oneNanoAfterDeadlineIs409AndNowIsNotTruncatedFirst() {
			// 마이크로초로 먼저 자르면 기한과 같아져 허용되는 값이다.
			clock.now = DEADLINE.plusNanos(1);
			assertThatThrownBy(() -> end()).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> {
				assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.code()).isEqualTo("EARLY_END_DEADLINE_PASSED");
				assertThat(e.getMessage()).contains("담당자에게 문의");
			});
			nothingWritten();
		}

		@Test
		void deadlinePassedDoesNotCompleteOrChangeAnything() {
			clock.now = DEADLINE.plusSeconds(86400);
			expect(() -> end(), HttpStatus.CONFLICT, "EARLY_END_DEADLINE_PASSED");
			assertThat(stored.getCompletedAtUtc()).isNull();
			assertThat(stored.getCompletedBy()).isNull();
			verify(assignments).findOwnership(ASSIGNMENT);
			verify(assignments).findByIdForUpdate(ASSIGNMENT);
			verifyNoMoreInteractions(assignments);
			nothingWritten();
		}

		@Test
		void deadlineJudgedAfterLockWait() {
			clock.now = DEADLINE.minusSeconds(1);
			given(streams.lockWorker(WORKER)).willAnswer(invocation -> { clock.now = DEADLINE.plusMillis(1); return Optional.of(stream); });
			expect(() -> end(), HttpStatus.CONFLICT, "EARLY_END_DEADLINE_PASSED");
			nothingWritten();
		}

		@Test
		void clockReadOnceAfterAllLocks() {
			var spyClock = mock(Clock.class);
			given(spyClock.instant()).willReturn(NOW);
			new WorkChangeRecorder(members, assignments, changes, streams, versions, spyClock).endWork(EMPLOYER, ASSIGNMENT, ACTUAL,
				Reason.OTHER, null, null);
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

		@Test
		void checkOrderNotStartedThenDeadlineThenRange() {
			// 시작 전 + 범위 밖 실제 종료 → 409 WORK_NOT_STARTED (범위 400보다 먼저)
			clock.now = START.minusSeconds(1);
			expect(() -> end(at(END.plusSeconds(10))), HttpStatus.CONFLICT, "WORK_NOT_STARTED");
			// 기한 초과 + 범위 밖 → 409 기한 초과
			clock.now = DEADLINE.plusSeconds(1);
			expect(() -> end(at(END.plusSeconds(10))), HttpStatus.CONFLICT, "EARLY_END_DEADLINE_PASSED");
		}
	}

	@Nested
	@DisplayName("동일 재요청·내용 충돌")
	class Replay {
		@Test
		void sameContentReturnsExistingWithoutClockOrWrites() {
			var previous = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(previous);
			clock.now = DEADLINE.plusSeconds(86400 * 10);
			clock.reads = 0;
			var result = end();
			assertThat(result.change()).isSameAs(previous);
			assertThat(result.timeZone()).isEqualTo("GMT+9");
			assertThat(snapshot(previous)).isEqualTo(before);
			assertThat(clock.reads).isZero();
			nothingWritten();
		}

		@Test
		void differentNotationSameInstantIsSame() {
			var previous = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.WEATHER, WeatherReason.RAIN, "비");
			for (var notation : List.of("2026-11-30T05:00:00.123456Z", "2026-11-30T14:00:00.123456+09:00", "2026-11-29T21:00:00.123456-08:00")) {
				assertThat(end(OffsetDateTime.parse(notation)).change()).as(notation).isSameAs(previous);
			}
			nothingWritten();
		}

		@Test
		void replayIgnoresNewTimeLimitsEvenWhenNowBeforeStartOrRangeChanged() {
			var previous = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.OTHER, null, null);
			clock.now = START.minusSeconds(86400);
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-11-30T09:00"));
			assertThat(recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, null, null).change()).isSameAs(previous);
		}

		@Test
		void detailComparedNormalized() {
			var previous = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.FARM_CIRCUMSTANCES, null, "  농장 사정 ");
			for (var detail : new String[] {"농장 사정", "\n농장 사정\t", "　농장 사정"}) {
				assertThat(recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.FARM_CIRCUMSTANCES, null, detail).change()).isSameAs(previous);
			}
			var empty = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.OTHER, null, null);
			for (var detail : new String[] {null, "", "  "}) {
				assertThat(recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, null, detail).change()).isSameAs(empty);
			}
			nothingWritten();
		}

		@Test
		void eachSingleDifferenceIs409AndUnchanged() {
			var previous = existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.WEATHER, WeatherReason.RAIN, "비");
			var before = snapshot(previous);
			clock.now = DEADLINE.plusSeconds(3600);
			String code = "EARLY_END_CONTENT_CONFLICT";
			expect(() -> end(ACTUAL.plusNanos(1000)), HttpStatus.CONFLICT, code);
			expect(() -> end(ACTUAL.plusSeconds(1)), HttpStatus.CONFLICT, code);
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, null, "비"), HttpStatus.CONFLICT, code);
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.WEATHER, WeatherReason.WIND, "비"), HttpStatus.CONFLICT, code);
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.WEATHER, WeatherReason.RAIN, "비 옴"), HttpStatus.CONFLICT, code);
			expect(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.WEATHER, WeatherReason.RAIN, null), HttpStatus.CONFLICT, code);
			assertThat(snapshot(previous)).isEqualTo(before);
			nothingWritten();
		}

		@Test
		void existingWithoutEffectiveIs503() {
			existing(Type.EARLY_ENDED, null, Reason.OTHER, null, null);
			unavailable(() -> recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.OTHER, null, null));
			nothingWritten();
		}

		@Test
		void existingRecordCheckedBeforeNewTimeChecks() {
			// 시작 전·기한 후 모두에서 기존 조기 종료와 다른 내용이면 시간 오류가 아닌 내용 충돌이다.
			existing(Type.EARLY_ENDED, ACTUAL_UTC, Reason.OTHER, null, null);
			clock.now = START.minusSeconds(1);
			expect(() -> end(), HttpStatus.CONFLICT, "EARLY_END_CONTENT_CONFLICT");
			clock.now = DEADLINE.plusSeconds(1);
			expect(() -> end(), HttpStatus.CONFLICT, "EARLY_END_CONTENT_CONFLICT");
		}
	}

	@Nested
	@DisplayName("저장과 보험 변경 버전")
	class Store {
		@Test
		void savesEarlyEndedSource() {
			var result = recorder.endWork(EMPLOYER, ASSIGNMENT, ACTUAL, Reason.WEATHER, WeatherReason.HEAT, "  폭염\n중단 ");
			var captor = ArgumentCaptor.forClass(WorkChange.class);
			verify(changes).saveAndFlush(captor.capture());
			var saved = captor.getValue();
			assertThat(result.change()).isSameAs(saved);
			assertThat(saved.getAssignmentId()).isEqualTo(ASSIGNMENT);
			assertThat(saved.getActorId()).isEqualTo(EMPLOYER);
			assertThat(saved.getChangeType()).isEqualTo(Type.EARLY_ENDED);
			assertThat(saved.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(saved.getWeatherReasonCode()).isEqualTo(WeatherReason.HEAT);
			assertThat(saved.getReasonDetail()).isEqualTo("폭염\n중단");
			// effectiveAt = 실제 종료(입력 마이크로초 보존), recorded/updated = 서버 시각 마이크로초 절삭
			assertThat(saved.getEffectiveAt()).isEqualTo(ACTUAL_UTC);
			assertThat(saved.getRecordedAt()).isEqualTo(LocalDateTime.parse("2026-11-30T09:00:00.123456"));
			assertThat(saved.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-11-30T09:00:00.123456"));
			assertThat(saved.getEffectiveAt()).isNotEqualTo(saved.getRecordedAt());
			assertThat(saved.getSourceVersion()).isEqualTo(1);
		}

		@Test
		void savesLinkedVersion() {
			end();
			var captor = ArgumentCaptor.forClass(WorkChangeVersion.class);
			verify(versions, times(1)).save(captor.capture());
			var v = captor.getValue();
			assertThat(stream.getLastSequence()).isEqualTo(5);
			assertThat(v.getSequenceNo()).isEqualTo(5);
			assertThat(v.getWorkerId()).isEqualTo(WORKER);
			assertThat(v.getAssignmentId()).isEqualTo(ASSIGNMENT);
			assertThat(v.getChangeId()).isEqualTo(CHANGE_ID);
			assertThat(v.getChangeType()).isEqualTo(Type.EARLY_ENDED);
			assertThat(v.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(v.getWeatherReasonCode()).isEqualTo(WeatherReason.RAIN);
			assertThat(v.getReasonDetail()).isEqualTo("비");
			assertThat(v.getEventTimeZone()).isEqualTo("GMT+9");
			assertThat(v.getSourceVersion()).isEqualTo(1);
			assertThat(v.getEffectiveAt()).isEqualTo(ACTUAL_UTC);
			assertThat(v.getRecordedAt()).isEqualTo(LocalDateTime.parse("2026-11-30T09:00:00.123456"));
			assertThat(v.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-11-30T09:00:00.123456"));
		}

		@Test
		void lockOrder() {
			end();
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
		void saveFailuresPropagate() {
			willThrow(new DataIntegrityViolationException("dup")).given(versions).save(any());
			assertThatThrownBy(() -> end()).isInstanceOf(DataIntegrityViolationException.class);
			willThrow(new DataAccessResourceFailureException("down")).given(changes).saveAndFlush(any());
			assertThatThrownBy(() -> end()).isInstanceOf(DataAccessResourceFailureException.class);
		}

		@Test
		void missingLocksAre503Or404() {
			given(streams.lockWorker(WORKER)).willReturn(Optional.empty());
			unavailable(() -> end());
			given(streams.lockWorker(WORKER)).willReturn(Optional.of(stream));
			given(assignments.findByIdForUpdate(ASSIGNMENT)).willReturn(Optional.empty());
			expect(() -> end(), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			nothingWritten();
		}

		@Test
		void doesNotTouchAgreementOrOthers() {
			var agreed = List.of(stored.getApplicationId(), stored.getAgreedStartAt(), stored.getAgreedEndAt(), stored.getAgreedTimeZone(),
				stored.getAgreedWorkDate(), stored.getAgreedDailyWage(), String.valueOf(stored.getCompletedAtUtc()));
			end();
			assertThat(List.of(stored.getApplicationId(), stored.getAgreedStartAt(), stored.getAgreedEndAt(), stored.getAgreedTimeZone(),
				stored.getAgreedWorkDate(), stored.getAgreedDailyWage(), String.valueOf(stored.getCompletedAtUtc()))).isEqualTo(agreed);
			verify(assignments).findOwnership(ASSIGNMENT);
			verify(assignments).findByIdForUpdate(ASSIGNMENT);
			verifyNoMoreInteractions(assignments);
			verify(changes).findForUpdate(ASSIGNMENT);
			verify(changes).saveAndFlush(any());
			verifyNoMoreInteractions(changes);
			verify(streams).ensureExists(WORKER);
			verify(streams).lockWorker(WORKER);
			verifyNoMoreInteractions(streams);
			// 기록기에는 지원·일정·공고 저장소 의존성이 없어 모집 재개·자리 복원 경로가 없다.
			var types = java.util.Arrays.stream(WorkChangeRecorder.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()).toList();
			assertThat(types).doesNotContain("JobApplicationRepository", "WorkScheduleRepository", "JobPostingRepository");
		}
	}

	private static WorkAssignmentRepository.Ownership ownership(long workerId, long employerId) {
		return new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return workerId; }
			public long getEmployerId() { return employerId; }
		};
	}
}

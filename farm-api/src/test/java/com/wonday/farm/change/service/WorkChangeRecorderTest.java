package com.wonday.farm.change.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.insurance.entity.ChangeStream;
import com.wonday.farm.insurance.entity.WorkChangeVersion;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 변경 최초 저장과 사유 보완의 저장 경계. Repository는 mock이고 Clock은 고정이다.
// 호출 순서(스트림 보장 → 잠금 → 중복 확인 → 저장)는 확인하지만, 실제 MySQL의 행 잠금·커밋 순서·롤백·
// 유니크 제약 동작은 증명하지 않는다 (통합 검증 대상). 취소/조기 종료 가능 여부(실제 작업 시작 판단)는
// 정책 미정이며 호출자 책임이라 여기서 판단하지 않는다.
class WorkChangeRecorderTest {
	private static final long EMPLOYER_ID = 10L;
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;
	private static final long CHANGE_ID = 77L;
	// 나노초까지 있는 서버 시각 → 마이크로초로 절삭되어 저장된다.
	private static final Instant NOW = Instant.parse("2026-11-30T01:23:45.123456789Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-30T01:23:45.123456");

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final WorkChangeRecorder recorder = recorder(Clock.fixed(NOW, ZoneOffset.UTC));
	private ChangeStream stream;
	private WorkAssignment stored;

	@BeforeEach
	void ownedAssignmentExists() {
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(ownership(WORKER_ID, EMPLOYER_ID)));
		stream = stream(WORKER_ID, 4);
		given(streams.lockWorker(WORKER_ID)).willReturn(Optional.of(stream));
		given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
		given(changes.saveAndFlush(any())).willAnswer(invocation -> {
			WorkChange saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", CHANGE_ID);
			return saved;
		});
	}

	@Nested
	class FirstRecord {
		@Test
		void cancellationUsesServerTimeTruncatedToMicrosAsEffectiveAndRecordedAt() {
			long id = recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.FARM_CIRCUMSTANCES, null, "농장 사정");

			assertThat(id).isEqualTo(CHANGE_ID);
			WorkChange saved = savedChange();
			assertThat(saved.getEffectiveAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getRecordedAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getUpdatedAt()).isEqualTo(NOW_UTC);
		}

		@Test
		void earlyEndUsesTheGivenActualEndInstantStoredAsUtc() {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.EARLY_ENDED,
				OffsetDateTime.parse("2026-11-30T15:00:00.000001+09:00"), Reason.WEATHER, WeatherReason.RAIN, null);

			WorkChange saved = savedChange();
			assertThat(saved.getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-30T06:00:00.000001"));
			assertThat(saved.getRecordedAt()).isEqualTo(NOW_UTC);
		}

		@Test
		void storesAllFieldsWithSourceVersionOne() {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.EARLY_ENDED,
				OffsetDateTime.parse("2026-11-30T15:00+09:00"), Reason.WEATHER, WeatherReason.HEAT, "폭염");

			WorkChange saved = savedChange();
			assertThat(saved.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
			assertThat(saved.getActorId()).isEqualTo(EMPLOYER_ID);
			assertThat(saved.getChangeType()).isEqualTo(Type.EARLY_ENDED);
			assertThat(saved.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(saved.getWeatherReasonCode()).isEqualTo(WeatherReason.HEAT);
			assertThat(saved.getReasonDetail()).isEqualTo("폭염");
			assertThat(saved.getSourceVersion()).isEqualTo(1);
		}

		@Test
		void savesAVersionWithTheNextStreamSequenceAndAgreedTimeZone() {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null);

			WorkChangeVersion version = savedVersions().getValue();
			assertThat(stream.getLastSequence()).isEqualTo(5);
			assertThat(version.getSequenceNo()).isEqualTo(5);
			assertThat(version.getWorkerId()).isEqualTo(WORKER_ID);
			assertThat(version.getChangeId()).isEqualTo(CHANGE_ID);
			assertThat(version.getSourceVersion()).isEqualTo(1);
			assertThat(version.getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
			assertThat(version.getChangeType()).isEqualTo(Type.CANCELLED);
			assertThat(version.getReasonCode()).isEqualTo(Reason.OTHER);
			assertThat(version.getEventTimeZone()).isEqualTo("GMT+9");
			assertThat(version.getEffectiveAt()).isEqualTo(NOW_UTC);
			assertThat(version.getRecordedAt()).isEqualTo(NOW_UTC);
			assertThat(version.getUpdatedAt()).isEqualTo(NOW_UTC);
		}

		@Test
		void locksTheWorkerStreamBeforeCheckingForDuplicatesAndSaving() {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null);

			var order = inOrder(members, assignments, streams, changes, versions);
			order.verify(members).findById(EMPLOYER_ID);
			order.verify(assignments).findOwnership(ASSIGNMENT_ID);
			order.verify(streams).ensureExists(WORKER_ID);
			order.verify(streams).lockWorker(WORKER_ID);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT_ID);
			order.verify(changes).findForUpdate(ASSIGNMENT_ID);
			order.verify(changes).saveAndFlush(any());
			order.verify(versions).save(any());
		}

		@Test
		void duplicateFirstRecordIsConflictAndConsumesNoSequence() {
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(existing()));

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null), HttpStatus.CONFLICT, "WORK_CHANGE_ALREADY_EXISTS");
			verify(changes, never()).saveAndFlush(any());
			verify(versions, never()).save(any());
			assertThat(stream.getLastSequence()).isEqualTo(4);
		}

		@Test
		void missingStreamRowAfterEnsureIsUnavailable() {
			given(streams.lockWorker(WORKER_ID)).willReturn(Optional.empty());

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			verify(changes, never()).saveAndFlush(any());
		}

		@Test
		void assignmentVanishingAfterOwnershipCheckIsUnavailable() {
			given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			verify(changes, never()).saveAndFlush(any());
		}

		@ParameterizedTest(name = "agreedTimeZone={0}")
		@NullSource
		@org.junit.jupiter.params.provider.ValueSource(strings = {"", "Seoul"})
		void invalidAgreedTimeZoneIsUnavailableAndNothingIsSaved(String zone) {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", zone);

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			verify(changes, never()).saveAndFlush(any());
			verify(versions, never()).save(any());
		}

		@Test
		void usesReadCommittedTransaction() throws Exception {
			var method = WorkChangeRecorder.class.getMethod("recordValidatedChange", long.class, long.class, Type.class,
				OffsetDateTime.class, Reason.class, WeatherReason.class, String.class);

			assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
		}
	}

	@Nested
	class TypeAndEndTime {
		@Test
		void missingTypeIsInvalid() {
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, null, null,
				Reason.OTHER, null, null));
		}

		@Test
		void earlyEndWithoutActualEndIsInvalid() {
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.EARLY_ENDED,
				null, Reason.OTHER, null, null));
		}

		@Test
		void cancellationWithActualEndIsInvalid() {
			// 취소 발생 시각은 서버가 부여한다. 호출자가 보낸 시각으로 대체할 수 없다.
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED,
				OffsetDateTime.parse("2026-11-30T15:00+09:00"), Reason.OTHER, null, null));
		}

		@Test
		void actualEndWithSubMicrosecondPrecisionIsInvalid() {
			// DATETIME(6)에 저장하면 잘릴 값은 받지 않는다.
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.EARLY_ENDED,
				OffsetDateTime.parse("2026-11-30T15:00:00.000000001+09:00"), Reason.OTHER, null, null));
		}
	}

	@Nested
	class ReasonValidation {
		@Test
		void missingReasonIsInvalid() {
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED,
				null, null, null, null));
		}

		@ParameterizedTest(name = "reason={0}")
		@EnumSource(value = Reason.class, names = {"FARM_CIRCUMSTANCES", "OTHER"})
		void weatherDetailOnlyAllowedForWeatherReason(Reason reason) {
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED,
				null, reason, WeatherReason.RAIN, null));
		}

		@ParameterizedTest(name = "weather={0}")
		@EnumSource(WeatherReason.class)
		void everyWeatherDetailIsAcceptedWithWeatherReason(WeatherReason weather) {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.WEATHER, weather, null);

			assertThat(savedChange().getWeatherReasonCode()).isEqualTo(weather);
		}

		@Test
		void weatherReasonWithoutWeatherDetailIsAccepted() {
			// 현재 코드·V5 CHECK 모두 WEATHER의 세부 사유를 선택값으로 둔다.
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.WEATHER, null, null);

			assertThat(savedChange().getWeatherReasonCode()).isNull();
		}

		@Test
		void detailUpTo65535Utf8BytesIsAccepted() {
			// 한글 한 글자 = UTF-8 3바이트. 21845자 = 65535바이트 (MySQL TEXT 최대).
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null,
				"가".repeat(21845));

			assertThat(savedChange().getReasonDetail()).hasSize(21845);
		}

		@Test
		void detailOver65535Utf8BytesIsInvalidEvenIfCharacterCountIsSmall() {
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED,
				null, Reason.OTHER, null, "가".repeat(21846)));
			assertInvalidWithoutLookup(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED,
				null, Reason.OTHER, null, "a".repeat(65536)));
		}
	}

	@Nested
	class Ownership {
		@Test
		void missingActorIsAccessDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());

			assertDeniedBeforeLocking();
		}

		@Test
		void workerActorIsAccessDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));

			assertDeniedBeforeLocking();
		}

		@Test
		void unsupportedRoleCodeIsAccessDenied() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(memberWithRoleCode(EMPLOYER_ID, "ADMIN")));

			assertDeniedBeforeLocking();
		}

		@Test
		void employerWhoDoesNotOwnTheAssignmentIsAccessDenied() {
			given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(ownership(WORKER_ID, 99L)));

			assertDeniedBeforeLocking();
		}

		@Test
		void missingAssignmentIsNotFound() {
			given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.empty());

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			verifyNoInteractions(streams, changes, versions);
		}

		@Test
		void revisionChecksOwnershipToo() {
			given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(ownership(WORKER_ID, 99L)));

			assertThatThrownBy(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null))
				.isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(streams, changes, versions);
		}

		private void assertDeniedBeforeLocking() {
			assertThatThrownBy(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null,
				Reason.OTHER, null, null)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(streams, changes, versions);
		}
	}

	@Nested
	class Revision {
		private WorkChange current;
		private WorkChangeVersion firstVersion;

		@BeforeEach
		void firstVersionExists() {
			current = change(CHANGE_ID, ASSIGNMENT_ID, Type.CANCELLED, Reason.WEATHER, WeatherReason.RAIN, "비",
				LocalDateTime.parse("2026-11-29T22:00"), LocalDateTime.parse("2026-11-29T22:00:00.5"));
			firstVersion = new WorkChangeVersion(WORKER_ID, 4, current, "GMT+9");
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(current));
		}

		@Test
		void addsVersionTwoWithRevisedReasonAndKeepsIdentityAndOriginalTimes() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.FARM_CIRCUMSTANCES, null, "농장 사정으로 정정");

			WorkChangeVersion revised = savedVersions().getValue();
			assertThat(revised.getChangeId()).isEqualTo(CHANGE_ID);
			assertThat(revised.getSourceVersion()).isEqualTo(2);
			assertThat(revised.getSequenceNo()).isEqualTo(5);
			assertThat(revised.getChangeType()).isEqualTo(Type.CANCELLED);
			assertThat(revised.getReasonCode()).isEqualTo(Reason.FARM_CIRCUMSTANCES);
			assertThat(revised.getWeatherReasonCode()).isNull();
			assertThat(revised.getReasonDetail()).isEqualTo("농장 사정으로 정정");
			assertThat(revised.getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:00"));
			assertThat(revised.getRecordedAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:00:00.5"));
			assertThat(revised.getUpdatedAt()).isEqualTo(NOW_UTC);
		}

		@Test
		void updatesTheCurrentChangeRow() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "기타");

			assertThat(current.getSourceVersion()).isEqualTo(2);
			assertThat(current.getReasonCode()).isEqualTo(Reason.OTHER);
			assertThat(current.getUpdatedAt()).isEqualTo(NOW_UTC);
			assertThat(current.getEffectiveAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:00"));
			verify(changes, never()).saveAndFlush(any());
		}

		@Test
		void earlierVersionObjectIsNotModified() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "기타");

			assertThat(firstVersion.getSourceVersion()).isEqualTo(1);
			assertThat(firstVersion.getReasonCode()).isEqualTo(Reason.WEATHER);
			assertThat(firstVersion.getWeatherReasonCode()).isEqualTo(WeatherReason.RAIN);
			assertThat(firstVersion.getReasonDetail()).isEqualTo("비");
			assertThat(firstVersion.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:00:00.5"));
		}

		@Test
		void eachRevisionIncrementsVersionAndSequence() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "1차");
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "2차");

			var saved = ArgumentCaptor.forClass(WorkChangeVersion.class);
			verify(versions, org.mockito.Mockito.times(2)).save(saved.capture());
			assertThat(saved.getAllValues()).extracting(WorkChangeVersion::getSourceVersion).containsExactly(2L, 3L);
			assertThat(saved.getAllValues()).extracting(WorkChangeVersion::getSequenceNo).containsExactly(5L, 6L);
			assertThat(saved.getAllValues()).extracting(WorkChangeVersion::getReasonDetail).containsExactly("1차", "2차");
		}

		@Test
		void locksStreamBeforeReadingTheChange() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null);

			var order = inOrder(streams, changes, versions);
			order.verify(streams).ensureExists(WORKER_ID);
			order.verify(streams).lockWorker(WORKER_ID);
			order.verify(changes).findForUpdate(ASSIGNMENT_ID);
			order.verify(versions).save(any());
		}

		@Test
		void missingChangeIsNotFoundAndConsumesNoSequence() {
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());

			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null),
				HttpStatus.NOT_FOUND, "WORK_CHANGE_NOT_FOUND");
			verify(versions, never()).save(any());
			assertThat(stream.getLastSequence()).isEqualTo(4);
		}

		@Test
		void invalidRevisedReasonIsRejectedWithoutTouchingTheChange() {
			assertInvalidWithoutLookup(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER,
				WeatherReason.WIND, null));
			assertThat(current.getSourceVersion()).isEqualTo(1);
		}

		@Test
		void missingStreamOrAssignmentOrZoneIsUnavailableAndChangeIsUntouched() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "Seoul");

			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertThat(current.getSourceVersion()).isEqualTo(1);

			given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());
			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");

			given(streams.lockWorker(WORKER_ID)).willReturn(Optional.empty());
			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			verify(versions, never()).save(any());
		}

		@Test
		void updatedAtFollowsTheClock() {
			var later = recorder(Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC));

			later.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, null);

			assertThat(savedVersions().getValue().getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-12-01T00:00"));
		}
	}

	private WorkChangeRecorder recorder(Clock clock) {
		return new WorkChangeRecorder(members, assignments, changes, streams, versions, clock);
	}

	private WorkChange savedChange() {
		var captor = ArgumentCaptor.forClass(WorkChange.class);
		verify(changes).saveAndFlush(captor.capture());
		return captor.getValue();
	}

	private ArgumentCaptor<WorkChangeVersion> savedVersions() {
		var captor = ArgumentCaptor.forClass(WorkChangeVersion.class);
		verify(versions).save(captor.capture());
		return captor;
	}

	private static WorkChange existing() {
		return change(CHANGE_ID, ASSIGNMENT_ID, Type.CANCELLED, Reason.OTHER, null, null, NOW_UTC, NOW_UTC);
	}

	private static WorkAssignmentRepository.Ownership ownership(long workerId, long employerId) {
		return new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return workerId; }
			public long getEmployerId() { return employerId; }
		};
	}

	private void assertInvalidWithoutLookup(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertError(call, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
		verify(members, never()).findById(any());
		verify(streams, never()).lockWorker(org.mockito.ArgumentMatchers.anyLong());
		verify(versions, never()).save(any());
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}
}

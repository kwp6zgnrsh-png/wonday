package com.wonday.farm.change.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.ChangeStreamRepository;
import com.wonday.farm.insurance.repository.WorkChangeVersionRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

// 완료된 근무에 대한 취소·조기 종료 최초 기록·사유 정정 거절과 잠금 호출 순서. 기존 WorkChangeRecorderTest는 수정하지 않고
// 이 클래스에서 완료 연결만 본다. Repository는 mock이므로 실제 MySQL 행 잠금 경합(완료 ↔ 취소 동시 요청)·데드락 부재·
// 롤백은 증명하지 않는다. Mockito 호출 순서는 코드가 그 순서로 잠금 메서드를 부른다는 것까지만 보여준다.
@DisplayName("변경 기록 저장 경계 × 근무 완료 (Repository mock)")
class WorkChangeRecorderCompletionTest {
	private static final long EMPLOYER_ID = 10L;
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;
	private static final LocalDateTime COMPLETED = LocalDateTime.parse("2026-11-30T08:30:00.000001");

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final ChangeStreamRepository streams = mock(ChangeStreamRepository.class);
	private final WorkChangeVersionRepository versions = mock(WorkChangeVersionRepository.class);
	private final WorkChangeRecorder recorder = new WorkChangeRecorder(members, assignments, changes, streams, versions,
		Clock.fixed(Instant.parse("2026-12-01T00:00:00.123456789Z"), ZoneOffset.UTC));
	private ChangeStream stream;
	private WorkAssignment stored;

	@BeforeEach
	void ownedAssignment() {
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return WORKER_ID; }
			public long getEmployerId() { return EMPLOYER_ID; }
		}));
		stream = stream(WORKER_ID, 4);
		given(streams.lockWorker(WORKER_ID)).willReturn(Optional.of(stream));
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
		given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());
		given(changes.saveAndFlush(any())).willAnswer(invocation -> {
			WorkChange saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 77L);
			return saved;
		});
	}

	private void completed(LocalDateTime at, Long by) {
		ReflectionTestUtils.setField(stored, "completedAtUtc", at);
		ReflectionTestUtils.setField(stored, "completedBy", by);
	}

	@Nested
	@DisplayName("최초 변경 기록")
	class FirstRecord {
		@ParameterizedTest(name = "{0}")
		@EnumSource(Type.class)
		@DisplayName("완료된 근무의 취소·조기 종료 기록은 409 WORK_ALREADY_COMPLETED, 변경·버전 저장 없음, 발행 순번 그대로")
		void completedIsConflict(Type type) {
			completed(COMPLETED, EMPLOYER_ID);
			var end = type == Type.EARLY_ENDED ? OffsetDateTime.parse("2026-11-30T15:00+09:00") : null;

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, type, end, Reason.OTHER, null, null),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			verify(changes, never()).saveAndFlush(any());
			verify(versions, never()).save(any());
			assertThat(stream.getLastSequence()).isEqualTo(4);
			assertThat(stored.getCompletedAtUtc()).isEqualTo(COMPLETED);
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);
		}

		@Test
		@DisplayName("완료 시각만·처리자만 있는 비정상 행도 완료로 보고 409 (변경 기록을 만들지 않음)")
		void halfCompletedIsConflict() {
			completed(COMPLETED, null);
			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");

			completed(null, EMPLOYER_ID);
			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			verify(changes, never()).saveAndFlush(any());
			verify(versions, never()).save(any());
			assertThat(stream.getLastSequence()).isEqualTo(4);
		}

		@Test
		@DisplayName("완료 여부를 기존 변경 중복(WORK_CHANGE_ALREADY_EXISTS)보다 먼저 판단한다")
		void completionCheckedBeforeDuplicate() {
			completed(COMPLETED, EMPLOYER_ID);
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(existing()));

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			verify(changes, never()).findForUpdate(ASSIGNMENT_ID);
		}

		@Test
		@DisplayName("호출 순서: 스트림 잠금 → 확정 근무 행 잠금 → 변경 행 잠금 → 저장 (기존 스트림 잠금 유지)")
		void lockOrder() {
			recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null);

			var order = inOrder(streams, assignments, changes, versions);
			order.verify(streams).ensureExists(WORKER_ID);
			order.verify(streams).lockWorker(WORKER_ID);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT_ID);
			order.verify(changes).findForUpdate(ASSIGNMENT_ID);
			order.verify(changes).saveAndFlush(any());
			order.verify(versions).save(any());
			verify(assignments, never()).findOwned(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
		}

		@Test
		@DisplayName("미완료 근무의 취소 기록은 기존대로 저장되고 순번이 1 증가 (직접 영향 회귀)")
		void notCompletedStillRecords() {
			long id = recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null);

			assertThat(id).isEqualTo(77L);
			assertThat(stream.getLastSequence()).isEqualTo(5);
			assertThat(stored.getCompletedAtUtc()).isNull();
		}

		@Test
		@DisplayName("[특성화] 완료로 거절돼도 그 전에 스트림 행 보장(ensureExists)·잠금은 이미 호출된다")
		void streamEnsuredBeforeRejection() {
			completed(COMPLETED, EMPLOYER_ID);

			assertError(() -> recorder.recordValidatedChange(EMPLOYER_ID, ASSIGNMENT_ID, Type.CANCELLED, null, Reason.OTHER, null, null),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			verify(streams).ensureExists(WORKER_ID);
			verify(streams).lockWorker(WORKER_ID);
		}
	}

	@Nested
	@DisplayName("사유 정정")
	class Revision {
		private WorkChange current;

		@BeforeEach
		void changeExists() {
			current = existing();
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(current));
		}

		@Test
		@DisplayName("완료된 근무의 사유 정정은 409 WORK_ALREADY_COMPLETED, 변경 행·버전·순번 그대로")
		void completedIsConflict() {
			completed(COMPLETED, EMPLOYER_ID);

			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.WEATHER, WeatherReason.RAIN, "정정"),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			assertThat(current.getSourceVersion()).isEqualTo(1);
			assertThat(current.getReasonCode()).isEqualTo(Reason.OTHER);
			assertThat(current.getReasonDetail()).isNull();
			assertThat(current.getUpdatedAt()).isEqualTo(LocalDateTime.parse("2026-11-29T22:00"));
			verify(versions, never()).save(any());
			assertThat(stream.getLastSequence()).isEqualTo(4);
		}

		@Test
		@DisplayName("처리자만 남은 비정상 행도 정정 거절")
		void halfCompletedIsConflict() {
			completed(null, EMPLOYER_ID);

			assertError(() -> recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "정정"),
				HttpStatus.CONFLICT, "WORK_ALREADY_COMPLETED");
			assertThat(current.getSourceVersion()).isEqualTo(1);
		}

		@Test
		@DisplayName("호출 순서: 스트림 잠금 → 확정 근무 행 잠금 → 변경 행 잠금 → 버전 저장")
		void lockOrder() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "정정");

			var order = inOrder(streams, assignments, changes, versions);
			order.verify(streams).lockWorker(WORKER_ID);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT_ID);
			order.verify(changes).findForUpdate(ASSIGNMENT_ID);
			order.verify(versions).save(any());
		}

		@Test
		@DisplayName("미완료 근무의 정정은 기존대로 버전 2·순번 5 (직접 영향 회귀)")
		void notCompletedStillRevises() {
			recorder.reviseReason(EMPLOYER_ID, ASSIGNMENT_ID, Reason.OTHER, null, "정정");

			assertThat(current.getSourceVersion()).isEqualTo(2);
			assertThat(stream.getLastSequence()).isEqualTo(5);
		}
	}

	private static WorkChange existing() {
		return change(77L, ASSIGNMENT_ID, Type.CANCELLED, Reason.OTHER, null, null,
			LocalDateTime.parse("2026-11-29T22:00"), LocalDateTime.parse("2026-11-29T22:00"));
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}
}

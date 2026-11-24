package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 확정 근무 상세 조회. Repository는 mock이므로 findOwned의 application.worker_id JOIN 필터가 실제 DB에서
// 다른 근로자의 근무를 걸러내는지는 증명하지 않는다 (통합 검증 대상). 서비스가 두 ID를 함께 넘기고
// 저장된 약정만 매핑하는지를 검증한다.
class WorkAssignmentQueryServiceTest {
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkAssignmentQueryService service =
		new WorkAssignmentQueryService(new InsuranceDataAccess(members), assignments);
	private WorkAssignment stored;

	@BeforeEach
	void workerAndAssignmentExist() {
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findOwned(WORKER_ID, ASSIGNMENT_ID)).willReturn(Optional.of(stored));
	}

	@Nested
	class InvalidIds {
		@ParameterizedTest(name = "assignmentId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositiveAssignmentIdIsRejectedBeforeAnyLookup(long assignmentId) {
			assertError(() -> service.find(WORKER_ID, assignmentId), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, assignments);
		}

		@ParameterizedTest(name = "workerId={0}")
		@ValueSource(longs = {0, -1})
		void nonPositiveWorkerIdIsRejectedBeforeAnyLookup(long workerId) {
			assertError(() -> service.find(workerId, ASSIGNMENT_ID), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, assignments);
		}
	}

	@Nested
	class Worker {
		@Test
		void missingWorkerIsWorkerNotFoundAndAssignmentIsNotRead() {
			given(members.findById(WORKER_ID)).willReturn(Optional.empty());

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
			verifyNoInteractions(assignments);
		}

		@Test
		void employerIdIsWorkerNotFound() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.EMPLOYER)));

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
			verifyNoInteractions(assignments);
		}

		@Test
		void unsupportedRoleIsWorkerNotFound() {
			given(members.findById(WORKER_ID))
				.willReturn(Optional.of(com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode(WORKER_ID, "ADMIN")));

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND");
		}
	}

	@Nested
	class Ownership {
		@Test
		void looksUpWithBothWorkerIdAndAssignmentId() {
			service.find(WORKER_ID, ASSIGNMENT_ID);

			verify(assignments).findOwned(WORKER_ID, ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
		}

		@Test
		void assignmentNotOwnedOrMissingIsNotFound() {
			// 다른 근로자의 근무는 findOwned가 비어 있게 돌려주는 계약이다. 존재 여부를 구분해 노출하지 않는다.
			given(assignments.findOwned(6L, ASSIGNMENT_ID)).willReturn(Optional.empty());
			given(members.findById(6L)).willReturn(Optional.of(member(6L, MemberRole.WORKER)));

			assertError(() -> service.find(6L, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
		}

		@Test
		void responseWorkerIdIsTheRequestedWorker() {
			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).workerId()).isEqualTo(WORKER_ID);
		}
	}

	@Nested
	class Mapping {
		@Test
		void mapsEveryStoredAgreementField() {
			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.assignmentId()).isEqualTo(ASSIGNMENT_ID);
			assertThat(response.agreedWorkDate()).isEqualTo(LocalDate.parse("2026-11-30"));
			assertThat(response.agreedTimeZone()).isEqualTo("GMT+9");
			assertThat(response.agreedWorkLocation()).isEqualTo("약정 작업지");
			assertThat(response.agreedCropCode()).isEqualTo("AGREED-CROP");
			assertThat(response.agreedWorkTypeCode()).isEqualTo("AGREED-WORK");
			assertThat(response.agreedDailyWage()).isEqualTo(120000L);
		}

		@Test
		void localAgreementTimesGetPlusNineOffsetWithoutShifting() {
			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.agreedStartAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T08:00+09:00"));
			assertThat(response.agreedEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00+09:00"));
			assertThat(response.agreedBreakStartAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T12:00+09:00"));
			assertThat(response.agreedBreakEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T13:00+09:00"));
		}

		@Test
		void utcConfirmationTimeIsShiftedToPlusNine() {
			// 저장값 UTC 2026-11-20T01:02:03.456789 → 현지 10:02:03.456789+09:00 (같은 순간)
			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).confirmedAt())
				.isEqualTo(OffsetDateTime.parse("2026-11-20T10:02:03.456789+09:00"));
		}

		@Test
		void noBreakIsNullOnBothSides() {
			ReflectionTestUtils.setField(stored, "agreedBreakStartAt", null);
			ReflectionTestUtils.setField(stored, "agreedBreakEndAt", null);

			var response = service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(response.agreedBreakStartAt()).isNull();
			assertThat(response.agreedBreakEndAt()).isNull();
		}

		@Test
		void returnsTheStoredTimeZoneTextVerbatim() {
			// 저장된 원문을 정규화하거나 Asia/Seoul로 바꾸지 않는다.
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "GMT+09:00");

			assertThat(service.find(WORKER_ID, ASSIGNMENT_ID).agreedTimeZone()).isEqualTo("GMT+09:00");
		}

		@Test
		void doesNotChangeTheEntityWhileReading() {
			var before = snapshot(stored);

			service.find(WORKER_ID, ASSIGNMENT_ID);

			assertThat(snapshot(stored)).isEqualTo(before);
		}

		@Test
		void isReadOnlyTransaction() throws Exception {
			var find = WorkAssignmentQueryService.class.getMethod("find", long.class, long.class);

			assertThat(find.getAnnotation(Transactional.class).readOnly()).isTrue();
		}
	}

	@Nested
	class IncompleteStoredAgreement {
		// V5 이전 행처럼 약정이 누락된 경우 현재 공고로 채우지 않고 503으로 응답하는 것이 코드의 계약이다.
		@ParameterizedTest(name = "missing {0}")
		@ValueSource(strings = {"agreedWorkDate", "confirmedAtUtc", "agreedTimeZone", "agreedStartAt", "agreedEndAt"})
		void missingRequiredAgreementIsUnavailable(String field) {
			ReflectionTestUtils.setField(stored, field, null);

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@ParameterizedTest(name = "only {0} missing")
		@ValueSource(strings = {"agreedBreakStartAt", "agreedBreakEndAt"})
		void halfMissingBreakIsUnavailable(String field) {
			ReflectionTestUtils.setField(stored, field, null);

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}

		@Test
		void unknownStoredTimeZoneIsUnavailable() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "Seoul");

			assertError(() -> service.find(WORKER_ID, ASSIGNMENT_ID), HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
		}
	}

	private static java.util.List<Object> snapshot(WorkAssignment value) {
		return java.util.List.of(value.getId(), value.getApplicationId(), value.getConfirmedAtUtc(),
			value.getAgreedWorkDate(), value.getAgreedTimeZone(), value.getAgreedWorkLocation(), value.getAgreedCropCode(),
			value.getAgreedWorkTypeCode(), value.getAgreedStartAt(), value.getAgreedEndAt(), value.getAgreedBreakStartAt(),
			value.getAgreedBreakEndAt(), value.getAgreedDailyWage());
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}
}

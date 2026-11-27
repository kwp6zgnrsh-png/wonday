package com.wonday.farm.work.service;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.memberWithRoleCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.config.WorkCompletionConfig;
import com.wonday.farm.work.dto.WorkCompletionResponse;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkCompletionException;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 근무 완료 서비스. Repository는 mock이고 Clock은 고정이다.
// 호출 순서(확정 근무 행 잠금 → 변경 행 잠금)와 @Transactional 선언은 확인하지만, 실제 MySQL의 행 잠금 대기·경합·
// 커밋/롤백, findOwnership의 JOIN 소유자 필터, V11 CHECK·FK 제약은 증명하지 않는다 (통합 검증 대상).
// 픽스처 약정: 2026-11-30 GMT+9 현지 08:00~17:00 → 약정 종료 = UTC 2026-11-30T08:00:00Z.
@DisplayName("근무 완료 서비스 (Repository mock, 고정 Clock)")
class WorkCompletionServiceTest {
	private static final long EMPLOYER_ID = 10L;
	private static final long WORKER_ID = 5L;
	private static final long ASSIGNMENT_ID = 31L;
	private static final Instant END = Instant.parse("2026-11-30T08:00:00Z");

	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkAssignmentRepository assignments = mock(WorkAssignmentRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private WorkAssignment stored;

	@BeforeEach
	void ownedFinishedAssignment() {
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
		given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(ownership(WORKER_ID, EMPLOYER_ID)));
		stored = assignment(ASSIGNMENT_ID);
		given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
		given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());
	}

	private WorkCompletionService at(Instant now) {
		return new WorkCompletionService(members, assignments, changes, Clock.fixed(now, ZoneOffset.UTC));
	}

	private WorkCompletionService at(String now) {
		return at(Instant.parse(now));
	}

	@Nested
	@DisplayName("정상 완료")
	class Success {
		@Test
		@DisplayName("서버 시각(UTC)과 세션 고용주 ID를 저장하고, 응답은 assignmentId와 약정 시간대(+09:00)의 completedAt")
		void storesServerTimeAndEmployer() {
			WorkCompletionResponse response = at("2026-11-30T09:15:30Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T09:15:30"));
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);
			assertThat(response.assignmentId()).isEqualTo(ASSIGNMENT_ID);
			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:15:30+09:00"));
			assertThat(response.completedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
		}

		@Test
		@DisplayName("나노초 서버 시각은 마이크로초로 절삭해 저장하고, 응답도 같은 마이크로초 값")
		void truncatesToMicros() {
			var response = at("2026-11-30T09:15:30.123456789Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T09:15:30.123456"));
			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:15:30.123456+09:00"));
			assertThat(response.completedAt().getNano() % 1000).isZero();
		}

		@Test
		@DisplayName("서버 시각이 약정 종료 시각과 정확히 같으면 완료할 수 있다")
		void exactlyAtEndIsAllowed() {
			var response = at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T08:00:00"));
			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00+09:00"));
		}

		@Test
		@DisplayName("종료 1마이크로초 뒤·며칠 뒤에도 완료할 수 있다")
		void afterEndIsAllowed() {
			at(END.plusNanos(1000)).complete(EMPLOYER_ID, ASSIGNMENT_ID);
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T08:00:00.000001"));

			stored = assignment(ASSIGNMENT_ID);
			given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
			at("2026-12-25T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-12-25T00:00"));
		}

		@Test
		@DisplayName("IANA 시간대(Asia/Seoul) 약정도 같은 종료 판단과 +09:00 응답")
		void regionZone() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "Asia/Seoul");

			var response = at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00+09:00"));
			assertThatThrownByCode(() -> {
				stored = assignment(ASSIGNMENT_ID);
				ReflectionTestUtils.setField(stored, "agreedTimeZone", "Asia/Seoul");
				given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
				at(END.minusNanos(1000)).complete(EMPLOYER_ID, ASSIGNMENT_ID);
			}, HttpStatus.CONFLICT, "WORK_NOT_FINISHED");
		}

		@Test
		@DisplayName("약정 시간대가 UTC가 아니면 응답 offset도 그 시간대를 따른다 (GMT-5 → -05:00)")
		void otherZoneOffset() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "GMT-5");
			// 현지 17:00 GMT-5 = UTC 22:00
			assertThatThrownByCode(() -> at("2026-11-30T21:59:59.999999Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.CONFLICT, "WORK_NOT_FINISHED");

			var response = at("2026-11-30T22:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);
			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:00-05:00"));
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T22:00"));
		}
	}

	@Nested
	@DisplayName("종료 전")
	class NotFinished {
		@ParameterizedTest(name = "now={0}")
		@ValueSource(strings = {"2026-11-30T07:59:59.999999Z", "2026-11-30T07:59:59.999999999Z", "2026-11-30T00:00:00Z",
			"2026-11-29T23:00:00Z"})
		@DisplayName("약정 종료 직전(1마이크로초 전, 절삭 후에도 이전인 나노초)은 409 WORK_NOT_FINISHED이고 저장하지 않는다")
		void beforeEndIsConflict(String now) {
			assertThatThrownByCode(() -> at(now).complete(EMPLOYER_ID, ASSIGNMENT_ID), HttpStatus.CONFLICT, "WORK_NOT_FINISHED");
			assertNotCompleted();
		}

		@Test
		@DisplayName("현재 공고·일정 값이 아니라 저장된 약정 종료 시각만 기준으로 판단한다 (약정 종료를 늦추면 같은 시각도 거절)")
		void usesStoredAgreedEnd() {
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-11-30T18:00"));

			assertThatThrownByCode(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID), HttpStatus.CONFLICT, "WORK_NOT_FINISHED");
		}
	}

	@Nested
	@DisplayName("요청·권한·소유권")
	class Access {
		@ParameterizedTest(name = "assignmentId={0}")
		@ValueSource(longs = {0, -1, Long.MIN_VALUE})
		@DisplayName("0·음수 ID는 조회 전에 400 INVALID_REQUEST")
		void nonPositiveId(long assignmentId) {
			assertThatThrownByCode(() -> at(END).complete(EMPLOYER_ID, assignmentId), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
			verifyNoInteractions(members, assignments, changes);
		}

		@Test
		@DisplayName("없는 회원·근로자·지원하지 않는 역할 코드는 AccessDeniedException이고 근무를 조회하지 않는다")
		void employerRoleIsRequiredFromDb() {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.empty());
			assertThatThrownBy(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID)).isInstanceOf(AccessDeniedException.class);

			// 세션에는 고용주였지만 DB 역할이 근로자로 바뀐 경우도 DB 값으로 거절한다.
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));
			assertThatThrownBy(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID)).isInstanceOf(AccessDeniedException.class);

			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(memberWithRoleCode(EMPLOYER_ID, "ADMIN")));
			assertThatThrownBy(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID)).isInstanceOf(AccessDeniedException.class);

			verifyNoInteractions(assignments, changes);
			assertNotCompleted();
		}

		@Test
		@DisplayName("없는 근무는 404 WORK_ASSIGNMENT_NOT_FOUND이고 잠금 조회를 하지 않는다")
		void missingAssignment() {
			given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.empty());

			assertThatThrownByCode(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			verify(assignments).findOwnership(ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
			verifyNoInteractions(changes);
		}

		@Test
		@DisplayName("다른 고용주의 근무는 없는 근무와 같은 404이고 잠그거나 변경하지 않는다")
		void otherEmployersAssignment() {
			given(assignments.findOwnership(ASSIGNMENT_ID)).willReturn(Optional.of(ownership(WORKER_ID, 99L)));

			assertThatThrownByCode(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			verify(assignments).findOwnership(ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
			verifyNoInteractions(changes);
			assertNotCompleted();
		}

		@Test
		@DisplayName("근로자 ID를 고용주 ID로 넘겨도(근로자=소유 연결의 workerId) 역할 확인에서 거절")
		void workerOfTheAssignmentIsDenied() {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, MemberRole.WORKER)));

			assertThatThrownBy(() -> at(END).complete(WORKER_ID, ASSIGNMENT_ID)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(assignments, changes);
		}

		@Test
		@DisplayName("소유권 확인 뒤 잠금 조회에서 근무가 사라지면 404")
		void vanishedBeforeLock() {
			given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.empty());

			assertThatThrownByCode(() -> at(END).complete(EMPLOYER_ID, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			verifyNoInteractions(changes);
		}
	}

	@Nested
	@DisplayName("취소·조기 종료")
	class Changed {
		@Test
		@DisplayName("취소 기록이 있으면 종료 이후라도 409 WORK_ALREADY_CANCELLED이고 완료를 저장하지 않는다")
		void cancelled() {
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(change(77L, ASSIGNMENT_ID, WorkChange.Type.CANCELLED,
				WorkChange.Reason.OTHER, null, null, LocalDateTime.parse("2026-11-29T00:00"), LocalDateTime.parse("2026-11-29T00:00"))));

			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.CONFLICT, "WORK_ALREADY_CANCELLED");
			assertNotCompleted();
		}

		@Test
		@DisplayName("조기 종료 기록이 있으면 409 WORK_ALREADY_EARLY_ENDED이고 변경 기록은 그대로")
		void earlyEnded() {
			var early = change(77L, ASSIGNMENT_ID, WorkChange.Type.EARLY_ENDED, WorkChange.Reason.WEATHER,
				WorkChange.WeatherReason.RAIN, "비", LocalDateTime.parse("2026-11-30T05:00"), LocalDateTime.parse("2026-11-30T05:00:01"));
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(early));

			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.CONFLICT, "WORK_ALREADY_EARLY_ENDED");
			assertNotCompleted();
			assertThat(early.getSourceVersion()).isEqualTo(1);
			assertThat(early.getChangeType()).isEqualTo(WorkChange.Type.EARLY_ENDED);
		}

		@Test
		@DisplayName("변경 기록이 있으면 종료 전이어도 WORK_NOT_FINISHED보다 취소 상태를 먼저 알린다")
		void changeIsCheckedBeforeEndTime() {
			given(changes.findForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(change(77L, ASSIGNMENT_ID, WorkChange.Type.CANCELLED,
				WorkChange.Reason.OTHER, null, null, LocalDateTime.parse("2026-11-29T00:00"), LocalDateTime.parse("2026-11-29T00:00"))));

			assertThatThrownByCode(() -> at("2026-11-29T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.CONFLICT, "WORK_ALREADY_CANCELLED");
		}
	}

	@Nested
	@DisplayName("저장된 약정 정보 이상")
	class Unavailable {
		@ParameterizedTest(name = "zone=[{0}]")
		@NullSource
		@ValueSource(strings = {"", "  ", "Seoul", "GMT+99", "Asia/Nowhere", "+09:00:00:00"})
		@DisplayName("약정 시간대 누락·해석 불가는 503 SERVICE_UNAVAILABLE이고 저장하지 않는다")
		void invalidZone(String zone) {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", zone);

			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertNotCompleted();
		}

		@Test
		@DisplayName("약정 종료 시각 누락은 503")
		void missingEnd() {
			ReflectionTestUtils.setField(stored, "agreedEndAt", null);

			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertNotCompleted();
		}

		@Test
		@DisplayName("서머타임 공백(존재하지 않는 현지 시각)·중복(두 offset) 종료 시각은 해석 불가로 503")
		void ambiguousLocalEnd() {
			ReflectionTestUtils.setField(stored, "agreedTimeZone", "America/New_York");
			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-03-08T02:30"));
			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");

			ReflectionTestUtils.setField(stored, "agreedEndAt", LocalDateTime.parse("2026-11-01T01:30"));
			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertNotCompleted();
		}

		@Test
		@DisplayName("완료 시각만 있거나 처리자만 있는 비정상 상태는 503이고 어느 값도 덮어쓰지 않는다")
		void halfCompletedState() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-30T08:30"));
			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T08:30"));
			assertThat(stored.getCompletedBy()).isNull();

			stored = assignment(ASSIGNMENT_ID);
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER_ID);
			given(assignments.findByIdForUpdate(ASSIGNMENT_ID)).willReturn(Optional.of(stored));
			assertThatThrownByCode(() -> at("2026-12-01T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID),
				HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			assertThat(stored.getCompletedAtUtc()).isNull();
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);
		}
	}

	@Nested
	@DisplayName("재요청")
	class Repeat {
		@Test
		@DisplayName("두 번째 요청은 나중 서버 시각이어도 최초 완료 시각을 반환하고 시각·처리자를 덮어쓰지 않는다")
		void returnsFirstCompletion() {
			var first = at("2026-11-30T09:00:00.000001Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);
			var second = at("2026-12-05T12:34:56.789Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(second).isEqualTo(first);
			assertThat(second.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T18:00:00.000001+09:00"));
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T09:00:00.000001"));
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);
		}

		@Test
		@DisplayName("이미 저장된 완료 행(DB에서 읽은 상태)도 최초 값으로 응답한다")
		void storedCompletion() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-30T08:30:00.5"));
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER_ID);

			var response = at("2026-12-10T00:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:30:00.5+09:00"));
			assertThat(stored.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-30T08:30:00.5"));
		}

		@Test
		@DisplayName("[특성화] 완료된 근무의 재요청은 약정 종료 판단보다 먼저 최초 값을 반환한다 (서버 시각이 종료 전이어도 200)")
		void repeatDoesNotRecheckEnd() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-30T08:30"));
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER_ID);

			var response = at("2026-11-30T07:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			assertThat(response.completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-30T17:30+09:00"));
		}

		@Test
		@DisplayName("완료된 근무라도 다른 고용주의 재요청은 404이고 최초 값을 노출하지 않는다")
		void otherEmployerCannotReadCompletion() {
			ReflectionTestUtils.setField(stored, "completedAtUtc", LocalDateTime.parse("2026-11-30T08:30"));
			ReflectionTestUtils.setField(stored, "completedBy", EMPLOYER_ID);
			given(members.findById(11L)).willReturn(Optional.of(member(11L, MemberRole.EMPLOYER)));

			assertThatThrownByCode(() -> at(END).complete(11L, ASSIGNMENT_ID), HttpStatus.NOT_FOUND, "WORK_ASSIGNMENT_NOT_FOUND");
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);
		}
	}

	@Nested
	@DisplayName("다른 데이터 보존과 잠금 순서 (mock 호출 기준)")
	class Isolation_ {
		@Test
		@DisplayName("약정·지원 ID·확정 시각 등 완료 두 필드 외의 값은 바뀌지 않는다")
		void onlyCompletionFieldsChange() throws Exception {
			Map<String, Object> before = snapshot(stored);

			at("2026-11-30T09:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			Map<String, Object> after = snapshot(stored);
			before.remove("completedAtUtc"); before.remove("completedBy");
			after.remove("completedAtUtc"); after.remove("completedBy");
			assertThat(after).isEqualTo(before);
		}

		@Test
		@DisplayName("변경 행은 잠금 조회만 하고 저장·삭제하지 않으며, 근무는 JPA 변경 감지에 맡겨 save 호출이 없다")
		void noWritesToChangesOrFeed() {
			at("2026-11-30T09:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			verify(changes).findForUpdate(ASSIGNMENT_ID);
			verifyNoMoreInteractions(changes);
			verify(assignments).findOwnership(ASSIGNMENT_ID);
			verify(assignments).findByIdForUpdate(ASSIGNMENT_ID);
			verifyNoMoreInteractions(assignments);
		}

		@Test
		@DisplayName("보험 변경 피드(스트림·버전) 의존성이 없다: 생성자 필드는 회원·근무·변경 저장소와 Clock뿐")
		void hasNoInsuranceFeedDependency() {
			assertThat(Arrays.stream(WorkCompletionService.class.getDeclaredFields()).map(Field::getType).map(Class::getSimpleName))
				.containsExactlyInAnyOrder("MemberRepository", "WorkAssignmentRepository", "WorkChangeRepository", "Clock");
		}

		@Test
		@DisplayName("역할 확인 → 소유권 → 확정 근무 행 잠금 → 변경 행 잠금 순서로 호출한다")
		void lockOrder() {
			at("2026-11-30T09:00:00Z").complete(EMPLOYER_ID, ASSIGNMENT_ID);

			var order = inOrder(members, assignments, changes);
			order.verify(members).findById(EMPLOYER_ID);
			order.verify(assignments).findOwnership(ASSIGNMENT_ID);
			order.verify(assignments).findByIdForUpdate(ASSIGNMENT_ID);
			order.verify(changes).findForUpdate(ASSIGNMENT_ID);
		}

		@Test
		@DisplayName("READ_COMMITTED 트랜잭션 선언, workCompletionClock 한정자, 설정 Clock은 UTC (선언 확인)")
		void declarations() throws Exception {
			var method = WorkCompletionService.class.getMethod("complete", long.class, long.class);
			assertThat(method.getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
			assertThat(method.getAnnotation(Transactional.class).readOnly()).isFalse();
			var clockParameter = WorkCompletionService.class.getConstructors()[0].getParameters()[3];
			assertThat(clockParameter.getAnnotation(Qualifier.class).value()).isEqualTo("workCompletionClock");
			var factory = WorkCompletionConfig.class.getDeclaredMethod("workCompletionClock");
			factory.setAccessible(true);
			assertThat(((Clock) factory.invoke(new WorkCompletionConfig())).getZone()).isEqualTo(ZoneOffset.UTC);
		}
	}

	private void assertNotCompleted() {
		assertThat(stored.getCompletedAtUtc()).isNull();
		assertThat(stored.getCompletedBy()).isNull();
	}

	private static Map<String, Object> snapshot(WorkAssignment assignment) throws Exception {
		Map<String, Object> values = new LinkedHashMap<>();
		for (Field field : WorkAssignment.class.getDeclaredFields()) {
			field.setAccessible(true);
			values.put(field.getName(), field.get(assignment));
		}
		return values;
	}

	private static WorkAssignmentRepository.Ownership ownership(long workerId, long employerId) {
		return new WorkAssignmentRepository.Ownership() {
			public long getWorkerId() { return workerId; }
			public long getEmployerId() { return employerId; }
		};
	}

	private static void assertThatThrownByCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
			String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkCompletionException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
		});
	}
}

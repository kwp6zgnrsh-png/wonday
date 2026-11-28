package com.wonday.farm.work.service;

import static com.wonday.farm.work.testsupport.SelectionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.wonday.farm.application.entity.ApplicationConditionAcknowledgement;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationConditionAcknowledgementRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 서비스 + 실제 SelectionTerms·엔티티, 저장소는 mock, 시계는 고정(또는 호출 순서 확인용 mock).
// mock은 실제 잠금 경합·정원 초과 방지·DB 롤백을 증명하지 않는다. 호출 순서와 저장 여부만 확인한다.
@DisplayName("WORK-02 선정 서비스 (저장소 mock·고정 Clock)")
class WorkSelectionServiceTest {
	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final ApplicationConditionAcknowledgementRepository acknowledgements = mock(ApplicationConditionAcknowledgementRepository.class);
	private final WorkSelectionRepository assignments = mock(WorkSelectionRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private JobPosting posting;
	private WorkSchedule schedule;
	private JobApplication application;

	private WorkSelectionService service(Clock clock) {
		return new WorkSelectionService(members, postings, schedules, applications, acknowledgements, assignments, changes, clock);
	}
	private WorkSelectionService.Result select(int version) { return service(clock(NOW)).select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(version)); }
	private WorkSelectionService.Result selectAt(Instant at, int version) {
		return service(clock(at)).select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(version));
	}
	private static void code(ThrowingCallable call, int status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkSelectionException.class, e -> {
			assertThat(e.status().value()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}
	private void nothingChanged() {
		assertThat(application.getStatus()).isEqualTo("PENDING");
		verify(assignments, never()).saveAndFlush(any());
		verify(assignments, never()).save(any());
	}
	private static ApplicationConditionAcknowledgement ack(long applicationId, int version) {
		var ack = new ApplicationConditionAcknowledgement(applicationId, version, LocalDateTime.parse("2026-11-27T01:00:00"), "{\"termsVersion\":" + version + "}");
		ReflectionTestUtils.setField(ack, "id", 9001L);
		return ack;
	}
	private static WorkChange change(WorkChange.Type type) {
		var change = mock(WorkChange.class);
		given(change.getChangeType()).willReturn(type);
		return change;
	}

	@BeforeEach
	void ready() {
		posting = posting(2);
		schedule = schedule(posting, WorkSchedule.Status.OPEN, 3);
		application = application(3, "PENDING", true);
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.WORKER)));
		given(postings.findOwnedForUpdate(POSTING, EMPLOYER)).willReturn(Optional.of(posting));
		given(schedules.findByIdAndJobPosting_Id(SCHEDULE, POSTING)).willReturn(Optional.of(schedule));
		given(applications.findScopedForUpdate(APPLICATION, SCHEDULE)).willReturn(Optional.of(application));
		given(assignments.findByApplicationForUpdate(APPLICATION)).willReturn(Optional.empty());
		given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(1L);
		given(applications.findBlockingAssignment(WORKER, SCHEDULE)).willReturn(Optional.empty());
		given(acknowledgements.findByApplicationIdAndTermsVersion(anyLong(), anyInt())).willReturn(Optional.empty());
		given(assignments.saveAndFlush(any())).willAnswer(call -> {
			WorkAssignment saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 7001L);
			return saved;
		});
	}

	@Test
	@DisplayName("선언: select는 READ_COMMITTED 트랜잭션 (실제 격리·롤백 동작은 확인하지 않음)")
	void transactional() throws Exception {
		var method = WorkSelectionService.class.getMethod("select", long.class, long.class, long.class, long.class, TermsVersionRequest.class);
		var tx = method.getAnnotation(Transactional.class);
		assertThat(tx).isNotNull();
		assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(tx.readOnly()).isFalse();
	}

	@Nested
	@DisplayName("권한·소속")
	class Ownership {
		@Test
		@DisplayName("고용주 회원 없음·근로자 역할·알 수 없는 역할 코드 → AccessDenied, 공고 잠금 없음")
		void role() {
			for (var member : new Object[] {null, member(EMPLOYER, MemberRole.WORKER), memberWithRole(EMPLOYER, "ADMIN")}) {
				given(members.findById(EMPLOYER)).willReturn(Optional.ofNullable((com.wonday.farm.member.Member) member));
				assertThatThrownBy(() -> select(3)).isInstanceOf(AccessDeniedException.class);
			}
			verifyNoInteractions(postings, assignments);
			verify(schedules, never()).findByIdAndJobPosting_Id(anyLong(), anyLong());
		}

		@Test
		@DisplayName("세션 고용주 ID로 소유 공고를 잠금 조회, 타인 공고 → 404 JOB_POSTING_NOT_FOUND")
		void foreignPosting() {
			given(postings.findOwnedForUpdate(POSTING, EMPLOYER)).willReturn(Optional.empty());
			code(() -> select(3), 404, "JOB_POSTING_NOT_FOUND");
			verify(postings).findOwnedForUpdate(POSTING, EMPLOYER);
			verify(schedules, never()).findByIdAndJobPosting_Id(anyLong(), anyLong());
			nothingChanged();
		}

		@Test
		@DisplayName("다른 공고의 일정 → 404 WORK_SCHEDULE_NOT_FOUND, 다른 날짜의 지원 → 404 APPLICATION_NOT_FOUND")
		void scope() {
			given(schedules.findByIdAndJobPosting_Id(SCHEDULE, POSTING)).willReturn(Optional.empty());
			code(() -> select(3), 404, "WORK_SCHEDULE_NOT_FOUND");
			verify(applications, never()).findScopedForUpdate(anyLong(), anyLong());
			given(schedules.findByIdAndJobPosting_Id(SCHEDULE, POSTING)).willReturn(Optional.of(schedule));
			given(applications.findScopedForUpdate(APPLICATION, SCHEDULE)).willReturn(Optional.empty());
			code(() -> select(3), 404, "APPLICATION_NOT_FOUND");
			verify(assignments, never()).findByApplicationForUpdate(anyLong());
			nothingChanged();
		}

		@Test
		@DisplayName("ID 0 이하·버전 null/0/음수 → 400 INVALID_REQUEST, 저장소 호출 없음")
		void invalid() {
			var s = service(clock(NOW));
			code(() -> s.select(0, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, -1, SCHEDULE, APPLICATION, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, 0, APPLICATION, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, SCHEDULE, 0, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, null), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(null)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(0)), 400, "INVALID_REQUEST");
			code(() -> s.select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(-3)), 400, "INVALID_REQUEST");
			verifyNoInteractions(members, postings, schedules, applications, assignments);
		}
	}

	@Nested
	@DisplayName("새 선정 대상·근로자")
	class Target {
		@Test
		@DisplayName("과거 회차 PENDING → 409 APPLICATION_NOT_PENDING")
		void retired() {
			ReflectionTestUtils.setField(application, "current", false);
			code(() -> select(3), 409, "APPLICATION_NOT_PENDING");
			verify(assignments, never()).saveAndFlush(any());
		}

		@ParameterizedTest
		@ValueSource(strings = {"SELECTED", "NOT_SELECTED", "CANCELLED"})
		@DisplayName("확정 근무 없는 PENDING 외 상태 → 409 APPLICATION_NOT_PENDING, 상태 유지")
		void notPending(String status) {
			ReflectionTestUtils.setField(application, "status", status);
			code(() -> select(3), 409, "APPLICATION_NOT_PENDING");
			assertThat(application.getStatus()).isEqualTo(status);
			verify(assignments, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("대상 회원 없음·고용주 역할 → 409 WORKER_UNAVAILABLE")
		void worker() {
			given(members.findById(WORKER)).willReturn(Optional.empty());
			code(() -> select(3), 409, "WORKER_UNAVAILABLE");
			given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.EMPLOYER)));
			code(() -> select(3), 409, "WORKER_UNAVAILABLE");
			nothingChanged();
		}

		@Test
		@DisplayName("다른 회차의 유효한 확정 근무 → 409 ALREADY_CONFIRMED (근로자·일정으로 조회)")
		void blocking() {
			given(applications.findBlockingAssignment(WORKER, SCHEDULE)).willReturn(Optional.of(6001L));
			code(() -> select(3), 409, "ALREADY_CONFIRMED");
			nothingChanged();
		}
	}

	@Nested
	@DisplayName("모집 상태·시작 시각")
	class StateAndTime {
		@ParameterizedTest
		@EnumSource(value = WorkSchedule.Status.class, names = {"OPEN", "PAUSED"})
		@DisplayName("OPEN·PAUSED 허용")
		void allowed(WorkSchedule.Status status) {
			ReflectionTestUtils.setField(schedule, "status", status);
			assertThat(select(3).replayed()).isFalse();
		}

		@ParameterizedTest
		@EnumSource(value = WorkSchedule.Status.class, names = {"CLOSED", "REMOVED"})
		@DisplayName("CLOSED·REMOVED → 409 SELECTION_NOT_ALLOWED, 변경 없음")
		void rejected(WorkSchedule.Status status) {
			ReflectionTestUtils.setField(schedule, "status", status);
			code(() -> select(3), 409, "SELECTION_NOT_ALLOWED");
			nothingChanged();
		}

		@Test
		@DisplayName("시작 1µs 전 허용, 정확히 시작 시각·이후 → 409 WORK_ALREADY_STARTED")
		void startBoundary() {
			assertThat(selectAt(START.minusNanos(1_000), 3).replayed()).isFalse();
			ReflectionTestUtils.setField(application, "status", "PENDING");
			code(() -> selectAt(START, 3), 409, "WORK_ALREADY_STARTED");
			code(() -> selectAt(START.plusSeconds(1), 3), 409, "WORK_ALREADY_STARTED");
			verify(assignments, times(1)).saveAndFlush(any());
		}

		@Test
		@DisplayName("서버 시각은 공고→일정→지원 잠금·정원·중복 검사를 마친 뒤 한 번만 읽고, 그 시각을 확정 시각으로 저장")
		void clockAfterChecks() {
			var clock = mock(Clock.class);
			given(clock.instant()).willReturn(NOW);
			service(clock).select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(3));
			var order = inOrder(members, postings, schedules, applications, assignments, clock);
			order.verify(members).findById(EMPLOYER);
			order.verify(postings).findOwnedForUpdate(POSTING, EMPLOYER);
			order.verify(schedules).findByIdAndJobPosting_Id(SCHEDULE, POSTING);
			order.verify(applications).findScopedForUpdate(APPLICATION, SCHEDULE);
			order.verify(assignments).findByApplicationForUpdate(APPLICATION);
			order.verify(schedules).countOccupyingAssignments(SCHEDULE);
			order.verify(applications).findBlockingAssignment(WORKER, SCHEDULE);
			order.verify(clock).instant();
			order.verify(assignments).saveAndFlush(any());
			verify(clock, times(1)).instant();
		}
	}

	@Nested
	@DisplayName("조건 버전·재확인")
	class Versions {
		@ParameterizedTest
		@ValueSource(ints = {2, 4})
		@DisplayName("고용주가 본 버전 ≠ 최신 → 409 TERMS_VERSION_CONFLICT")
		void stale(int seen) {
			code(() -> select(seen), 409, "TERMS_VERSION_CONFLICT");
			nothingChanged();
		}

		@Test
		@DisplayName("최초 지원 버전 = 최신이면 재확인 조회 없이 선정")
		void initialLatest() {
			assertThat(select(3).replayed()).isFalse();
			verifyNoInteractions(acknowledgements);
		}

		@Test
		@DisplayName("최초 지원 버전 ≠ 최신: 같은 지원의 최신 버전 재확인 기록이 있으면 선정")
		void reconfirmed() {
			ReflectionTestUtils.setField(application, "appliedTermsVersion", 2);
			given(acknowledgements.findByApplicationIdAndTermsVersion(APPLICATION, 3)).willReturn(Optional.of(ack(APPLICATION, 3)));
			assertThat(select(3).body().status()).isEqualTo("SELECTED");
			assertThat(application.getAppliedTermsVersion()).isEqualTo(2);
		}

		@Test
		@DisplayName("최신 미확인 → 409 CONDITION_RECONFIRMATION_REQUIRED (다른 지원·이전 버전 기록으로 통과 안 함), 지원 PENDING 유지")
		void required() {
			ReflectionTestUtils.setField(application, "appliedTermsVersion", 2);
			given(acknowledgements.findByApplicationIdAndTermsVersion(APPLICATION, 2)).willReturn(Optional.of(ack(APPLICATION, 2)));
			given(acknowledgements.findByApplicationIdAndTermsVersion(502L, 3)).willReturn(Optional.of(ack(502L, 3)));
			code(() -> select(3), 409, "CONDITION_RECONFIRMATION_REQUIRED");
			verify(acknowledgements).findByApplicationIdAndTermsVersion(APPLICATION, 3);
			nothingChanged();
		}

		@Test
		@DisplayName("최초 지원 버전이 없는 기존 지원 + 재확인 없음 → 409 CONDITION_RECONFIRMATION_REQUIRED (추측하지 않음)")
		void legacyNull() {
			ReflectionTestUtils.setField(application, "appliedTermsVersion", null);
			code(() -> select(3), 409, "CONDITION_RECONFIRMATION_REQUIRED");
			nothingChanged();
		}

		@Test
		@DisplayName("좌표 없는 기존 공고 → 409 WORK_LOCATION_INCOMPLETE, 시간대 불명 → 409 WORK_TERMS_UNAVAILABLE")
		void incomplete() {
			ReflectionTestUtils.setField(posting, "latitude", null);
			code(() -> select(3), 409, "WORK_LOCATION_INCOMPLETE");
			assertThat(posting.getLatitude()).isNull();
			ReflectionTestUtils.setField(posting, "latitude", new BigDecimal("37.123456"));
			ReflectionTestUtils.setField(posting, "timeZone", "Mars/Base");
			code(() -> select(3), 409, "WORK_TERMS_UNAVAILABLE");
			nothingChanged();
		}
	}

	@Nested
	@DisplayName("정원")
	class Capacity {
		@Test
		@DisplayName("실제 확정 집계 1 < 정원 2 → 선정")
		void below() {
			assertThat(select(3).replayed()).isFalse();
			verify(schedules).countOccupyingAssignments(SCHEDULE);
		}

		@ParameterizedTest
		@ValueSource(longs = {2, 3})
		@DisplayName("집계 = 정원·초과 → 409 CAPACITY_FULL, 변경 없음")
		void full(long occupied) {
			given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(occupied);
			code(() -> select(3), 409, "CAPACITY_FULL");
			nothingChanged();
		}
	}

	@Nested
	@DisplayName("신규 선정 성공")
	class Success {
		@Test
		@DisplayName("201 대상: SELECTED·확정 근무 한 건·서버 시각·최초 지원 시각/버전/회차 유지·응답 +09:00 마이크로초")
		void created() {
			var appliedAt = application.getAppliedAtUtc();
			var result = select(3);
			assertThat(result.replayed()).isFalse();
			assertThat(application.getStatus()).isEqualTo("SELECTED");
			assertThat(application.getAppliedAtUtc()).isEqualTo(appliedAt);
			assertThat(application.getAppliedTermsVersion()).isEqualTo(3);
			assertThat(application.getApplicationRound()).isEqualTo(2);
			var captor = ArgumentCaptor.forClass(WorkAssignment.class);
			verify(assignments, times(1)).saveAndFlush(captor.capture());
			var saved = captor.getValue();
			assertThat(saved.getApplicationId()).isEqualTo(APPLICATION);
			assertThat(saved.getConfirmedAtUtc()).isEqualTo(NOW_UTC);
			assertThat(saved.getConfirmedAt()).isEqualTo(NOW_UTC);
			assertThat(saved.getAgreedTermsVersion()).isEqualTo(3);
			var body = result.body();
			assertThat(body.applicationId()).isEqualTo(APPLICATION);
			assertThat(body.workScheduleId()).isEqualTo(SCHEDULE);
			assertThat(body.workerId()).isEqualTo(WORKER);
			assertThat(body.status()).isEqualTo("SELECTED");
			assertThat(body.assignmentId()).isEqualTo(7001L);
			assertThat(body.agreedTermsVersion()).isEqualTo(3);
			assertThat(body.confirmedAt()).isEqualTo(OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00"));
			assertThat(body.confirmedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
			assertThat(body.workStatus()).isEqualTo("CONFIRMED");
			assertThat(body.completedAt()).isNull();
		}

		@Test
		@DisplayName("저장 실패(DataIntegrityViolation)는 그대로 전파되어 결과를 반환하지 않음 (DB 롤백은 미검증)")
		void saveFails() {
			willThrow(new DataIntegrityViolationException("duplicate")).given(assignments).saveAndFlush(any());
			assertThatThrownBy(() -> select(3)).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Nested
	@DisplayName("기존 결과 재요청")
	class Replay {
		private WorkAssignment existing;

		@BeforeEach
		void selected() {
			existing = assignment(6100L, schedule, LocalDateTime.parse("2026-11-25T02:00:00.654321"));
			ReflectionTestUtils.setField(application, "status", "SELECTED");
			given(assignments.findByApplicationForUpdate(APPLICATION)).willReturn(Optional.of(existing));
			given(changes.findForUpdate(6100L)).willReturn(Optional.empty());
		}
		private void noNewWork() {
			verify(assignments, never()).saveAndFlush(any());
			verify(schedules, never()).countOccupyingAssignments(anyLong());
			verify(applications, never()).findBlockingAssignment(anyLong(), anyLong());
		}

		@Test
		@DisplayName("정상 확정 → 200 대상, 기존 ID·확정 시각·약정 버전, 새 assignment·정원 조회·시각 조회 없음")
		void confirmed() {
			var clock = mock(Clock.class);
			var result = service(clock).select(EMPLOYER, POSTING, SCHEDULE, APPLICATION, new TermsVersionRequest(3));
			assertThat(result.replayed()).isTrue();
			assertThat(result.body().assignmentId()).isEqualTo(6100L);
			assertThat(result.body().confirmedAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T11:00:00.654321+09:00"));
			assertThat(result.body().agreedTermsVersion()).isEqualTo(3);
			assertThat(result.body().workStatus()).isEqualTo("CONFIRMED");
			assertThat(existing.getConfirmedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-25T02:00:00.654321"));
			verifyNoInteractions(clock, acknowledgements);
			noNewWork();
		}

		@Test
		@DisplayName("현재 CLOSED·시작 경과·최신 버전 5·요청 버전 2여도 정상 기존 결과 200")
		void ignoresCurrentTerms() {
			ReflectionTestUtils.setField(schedule, "status", WorkSchedule.Status.CLOSED);
			ReflectionTestUtils.setField(schedule, "termsVersion", 5);
			var result = selectAt(START.plusSeconds(3600), 2);
			assertThat(result.replayed()).isTrue();
			assertThat(result.body().agreedTermsVersion()).isEqualTo(3);
			noNewWork();
		}

		@Test
		@DisplayName("재요청에도 역할·소유권·소속 검사 적용 (근로자 세션 403, 타인 공고 404, 다른 일정 404)")
		void stillScoped() {
			given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.WORKER)));
			assertThatThrownBy(() -> select(3)).isInstanceOf(AccessDeniedException.class);
			given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
			given(postings.findOwnedForUpdate(POSTING, EMPLOYER)).willReturn(Optional.empty());
			code(() -> select(3), 404, "JOB_POSTING_NOT_FOUND");
			given(postings.findOwnedForUpdate(POSTING, EMPLOYER)).willReturn(Optional.of(posting));
			given(applications.findScopedForUpdate(APPLICATION, SCHEDULE)).willReturn(Optional.empty());
			code(() -> select(3), 404, "APPLICATION_NOT_FOUND");
			verify(assignments, never()).findByApplicationForUpdate(anyLong());
		}

		@Test
		@DisplayName("CANCELLED → 409 WORK_ASSIGNMENT_CANCELLED, 되살리지 않음")
		void cancelled() {
			var change = change(WorkChange.Type.CANCELLED);
			given(changes.findForUpdate(6100L)).willReturn(Optional.of(change));
			code(() -> select(3), 409, "WORK_ASSIGNMENT_CANCELLED");
			assertThat(application.getStatus()).isEqualTo("SELECTED");
			noNewWork();
		}

		@Test
		@DisplayName("EARLY_ENDED → 200 기존 ID·EARLY_ENDED")
		void earlyEnded() {
			var change = change(WorkChange.Type.EARLY_ENDED);
			given(changes.findForUpdate(6100L)).willReturn(Optional.of(change));
			var result = select(3);
			assertThat(result.replayed()).isTrue();
			assertThat(result.body().assignmentId()).isEqualTo(6100L);
			assertThat(result.body().workStatus()).isEqualTo("EARLY_ENDED");
			noNewWork();
		}

		@Test
		@DisplayName("COMPLETED → 200 기존 ID·COMPLETED·완료 시각 +09:00")
		void completed() {
			ReflectionTestUtils.setField(existing, "completedAtUtc", LocalDateTime.parse("2026-11-26T09:00:00"));
			ReflectionTestUtils.setField(existing, "completedBy", EMPLOYER);
			var result = select(3);
			assertThat(result.body().workStatus()).isEqualTo("COMPLETED");
			assertThat(result.body().completedAt()).isEqualTo(OffsetDateTime.parse("2026-11-26T18:00:00+09:00"));
			noNewWork();
		}

		@Test
		@DisplayName("완료 시각·완료자 중 하나만 있으면 503 (상태 추측 안 함)")
		void halfCompleted() {
			ReflectionTestUtils.setField(existing, "completedAtUtc", LocalDateTime.parse("2026-11-26T09:00:00"));
			code(() -> select(3), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("기존 약정 버전·UTC 확정 시각이 없는 과거 행 → null 그대로 (현재 버전·시각으로 채우지 않음)")
		void legacy() {
			ReflectionTestUtils.setField(existing, "agreedTermsVersion", null);
			ReflectionTestUtils.setField(existing, "confirmedAtUtc", null);
			var result = select(3);
			assertThat(result.body().agreedTermsVersion()).isNull();
			assertThat(result.body().confirmedAt()).isNull();
			assertThat(existing.getAgreedTermsVersion()).isNull();
			noNewWork();
		}

		@Test
		@DisplayName("확정 근무는 있는데 지원이 PENDING → 409 APPLICATION_STATE_CONFLICT")
		void mismatched() {
			ReflectionTestUtils.setField(application, "status", "PENDING");
			code(() -> select(3), 409, "APPLICATION_STATE_CONFLICT");
			assertThat(application.getStatus()).isEqualTo("PENDING");
			noNewWork();
		}
	}
}

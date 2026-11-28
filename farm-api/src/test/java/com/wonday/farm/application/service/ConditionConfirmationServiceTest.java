package com.wonday.farm.application.service;

import static com.wonday.farm.work.testsupport.SelectionFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.wonday.farm.application.dto.ConditionConfirmationResponse;
import com.wonday.farm.application.entity.ApplicationConditionAcknowledgement;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationConditionAcknowledgementRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.SelectionTerms;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// 실제 서비스 + 실제 SelectionTerms·매퍼·JsonMapper, 저장소는 mock, 시계는 고정.
// 실제 UNIQUE(지원 ID·버전) 제약·동시 재확인·롤백은 확인하지 않는다.
@DisplayName("WORK-04 근로자 조건 재확인 서비스 (저장소 mock·고정 Clock)")
class ConditionConfirmationServiceTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final ApplicationConditionAcknowledgementRepository acknowledgements = mock(ApplicationConditionAcknowledgementRepository.class);
	private final WorkSelectionRepository assignments = mock(WorkSelectionRepository.class);
	private JobPosting posting;
	private WorkSchedule schedule;
	private JobApplication application;

	private ConditionConfirmationService service(Clock clock) {
		return new ConditionConfirmationService(members, postings, schedules, applications, acknowledgements, assignments, JSON, clock);
	}
	private ConditionConfirmationResponse confirm(int version) { return confirmAt(NOW, version); }
	private ConditionConfirmationResponse confirmAt(Instant at, int version) {
		return service(clock(at)).confirm(WORKER, APPLICATION, new TermsVersionRequest(version));
	}
	private static void code(ThrowingCallable call, int status, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkSelectionException.class, e -> {
			assertThat(e.status().value()).isEqualTo(status);
			assertThat(e.code()).isEqualTo(code);
		});
	}
	private void nothingSaved() {
		verify(acknowledgements, never()).saveAndFlush(any());
		verify(acknowledgements, never()).save(any());
		verify(assignments, never()).saveAndFlush(any());
		assertThat(application.getAppliedTermsVersion()).isEqualTo(2);
	}
	private static JobApplicationRepository.OwnedLocation location() {
		return new JobApplicationRepository.OwnedLocation() {
			public long getPostingId() { return POSTING; }
			public long getScheduleId() { return SCHEDULE; }
		};
	}
	private static ApplicationConditionAcknowledgement saved(long id, int version, LocalDateTime at, String payload) {
		var ack = new ApplicationConditionAcknowledgement(APPLICATION, version, at, payload);
		ReflectionTestUtils.setField(ack, "id", id);
		return ack;
	}

	@BeforeEach
	void ready() {
		posting = posting(2);
		schedule = schedule(posting, WorkSchedule.Status.OPEN, 3);
		application = application(2, "PENDING", true);
		given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.WORKER)));
		given(applications.findOwnedLocation(WORKER, APPLICATION)).willReturn(Optional.of(location()));
		given(postings.findByIdForUpdate(POSTING)).willReturn(Optional.of(posting));
		given(schedules.findByIdAndJobPosting_Id(SCHEDULE, POSTING)).willReturn(Optional.of(schedule));
		given(applications.findScopedForUpdate(APPLICATION, SCHEDULE)).willReturn(Optional.of(application));
		given(assignments.findByApplicationForUpdate(APPLICATION)).willReturn(Optional.empty());
		given(acknowledgements.findByApplicationIdAndTermsVersion(anyLong(), org.mockito.ArgumentMatchers.anyInt())).willReturn(Optional.empty());
		given(acknowledgements.saveAndFlush(any())).willAnswer(call -> {
			ApplicationConditionAcknowledgement ack = call.getArgument(0);
			ReflectionTestUtils.setField(ack, "id", 9001L);
			return ack;
		});
	}

	@Test
	@DisplayName("선언: confirm은 READ_COMMITTED 쓰기 트랜잭션")
	void transactional() throws Exception {
		var tx = ConditionConfirmationService.class.getMethod("confirm", long.class, long.class, TermsVersionRequest.class)
			.getAnnotation(Transactional.class);
		assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(tx.readOnly()).isFalse();
	}

	@Test
	@DisplayName("근로자 회원 없음·고용주 역할·알 수 없는 역할 → AccessDenied, 소속 조회 없음")
	void role() {
		for (Member member : new Member[] {null, member(WORKER, MemberRole.EMPLOYER), memberWithRole(WORKER, "ADMIN")}) {
			given(members.findById(WORKER)).willReturn(Optional.ofNullable(member));
			assertThatThrownBy(() -> confirm(3)).isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(applications, postings, acknowledgements);
	}

	@Test
	@DisplayName("타인 지원(본인 소속 조회 없음) → 404 APPLICATION_NOT_FOUND, 잠금 없음")
	void foreign() {
		given(applications.findOwnedLocation(WORKER, APPLICATION)).willReturn(Optional.empty());
		code(() -> confirm(3), 404, "APPLICATION_NOT_FOUND");
		verifyNoInteractions(postings);
		nothingSaved();
	}

	@Test
	@DisplayName("잠금 뒤 지원 소유자가 다르면 404, 공고·일정이 사라졌으면 404")
	void ownerAfterLock() {
		ReflectionTestUtils.setField(application, "workerId", 999L);
		code(() -> confirm(3), 404, "APPLICATION_NOT_FOUND");
		given(postings.findByIdForUpdate(POSTING)).willReturn(Optional.empty());
		code(() -> confirm(3), 404, "APPLICATION_NOT_FOUND");
		given(postings.findByIdForUpdate(POSTING)).willReturn(Optional.of(posting));
		given(schedules.findByIdAndJobPosting_Id(SCHEDULE, POSTING)).willReturn(Optional.empty());
		code(() -> confirm(3), 404, "APPLICATION_NOT_FOUND");
		nothingSaved();
	}

	@Test
	@DisplayName("과거 회차 → 409 APPLICATION_NOT_PENDING")
	void retired() {
		ReflectionTestUtils.setField(application, "current", false);
		code(() -> confirm(3), 409, "APPLICATION_NOT_PENDING");
		nothingSaved();
	}

	@ParameterizedTest
	@ValueSource(strings = {"SELECTED", "NOT_SELECTED", "CANCELLED"})
	@DisplayName("지원 대기가 아닌 상태 → 409 APPLICATION_NOT_PENDING")
	void notPending(String status) {
		ReflectionTestUtils.setField(application, "status", status);
		code(() -> confirm(3), 409, "APPLICATION_NOT_PENDING");
		nothingSaved();
	}

	@Test
	@DisplayName("PENDING인데 확정 근무가 연결된 비정상 건 → 409 APPLICATION_NOT_PENDING")
	void pendingWithAssignment() {
		given(assignments.findByApplicationForUpdate(APPLICATION)).willReturn(Optional.of(mock(WorkAssignment.class)));
		code(() -> confirm(3), 409, "APPLICATION_NOT_PENDING");
		nothingSaved();
	}

	@ParameterizedTest
	@EnumSource(value = WorkSchedule.Status.class, names = {"CLOSED", "REMOVED"})
	@DisplayName("CLOSED·REMOVED → 409 SELECTION_NOT_ALLOWED, PAUSED는 허용")
	void state(WorkSchedule.Status status) {
		ReflectionTestUtils.setField(schedule, "status", status);
		code(() -> confirm(3), 409, "SELECTION_NOT_ALLOWED");
		nothingSaved();
		ReflectionTestUtils.setField(schedule, "status", WorkSchedule.Status.PAUSED);
		assertThat(confirm(3).conditionConfirmation().latestTermsConfirmed()).isTrue();
	}

	@Test
	@DisplayName("시작 1µs 전 허용, 정확히 시작 시각 → 409 WORK_ALREADY_STARTED")
	void startBoundary() {
		code(() -> confirmAt(START, 3), 409, "WORK_ALREADY_STARTED");
		nothingSaved();
		assertThat(confirmAt(START.minusNanos(1000), 3).conditionConfirmation().acknowledgementId()).isEqualTo(9001L);
	}

	@Test
	@DisplayName("요청 버전 ≠ 최신 → 409 TERMS_VERSION_CONFLICT (예전 버전 기록이 있어도)")
	void staleVersion() {
		given(acknowledgements.findByApplicationIdAndTermsVersion(APPLICATION, 2))
			.willReturn(Optional.of(saved(8001L, 2, LocalDateTime.parse("2026-11-26T00:00:00"), "{\"termsVersion\":2}")));
		code(() -> confirm(2), 409, "TERMS_VERSION_CONFLICT");
		code(() -> confirm(4), 409, "TERMS_VERSION_CONFLICT");
		nothingSaved();
	}

	@Test
	@DisplayName("최초 재확인: 지원 ID·버전·서버 UTC 시각·잠근 서버 데이터의 조건 원문으로 한 건 저장, 최초 지원 정보 불변, 선정·자리 확보 없음")
	void firstConfirmation() {
		var appliedAt = application.getAppliedAtUtc();
		var response = confirm(3);
		var captor = ArgumentCaptor.forClass(ApplicationConditionAcknowledgement.class);
		verify(acknowledgements, times(1)).saveAndFlush(captor.capture());
		var ack = captor.getValue();
		assertThat(ack.getApplicationId()).isEqualTo(APPLICATION);
		assertThat(ack.getTermsVersion()).isEqualTo(3);
		assertThat(ack.getAcknowledgedAtUtc()).isEqualTo(NOW_UTC);
		assertThat(ack.getConditionsPayload()).isEqualTo(SelectionTerms.snapshot(schedule, JSON));
		var payload = JSON.readTree(ack.getConditionsPayload());
		assertThat(payload.propertyNames()).contains("workLocation", "latitude", "longitude", "description", "headcount", "dailyWage",
			"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt", "termsVersion");
		assertThat(application.getStatus()).isEqualTo("PENDING");
		assertThat(application.getAppliedTermsVersion()).isEqualTo(2);
		assertThat(application.getAppliedAtUtc()).isEqualTo(appliedAt);
		assertThat(application.getApplicationRound()).isEqualTo(2);
		verify(assignments, never()).saveAndFlush(any());
		verify(schedules, never()).countOccupyingAssignments(anyLong());
		assertThat(response.applicationId()).isEqualTo(APPLICATION);
		assertThat(response.applicationRound()).isEqualTo(2);
		assertThat(response.status()).isEqualTo("PENDING");
		var info = response.conditionConfirmation();
		assertThat(info.source()).isEqualTo("RECONFIRMATION");
		assertThat(info.confirmedTermsVersion()).isEqualTo(3);
		assertThat(info.latestTermsConfirmed()).isTrue();
		assertThat(info.acknowledgementId()).isEqualTo(9001L);
		assertThat(info.acknowledgedTermsVersion()).isEqualTo(3);
		assertThat(info.acknowledgedAt()).isEqualTo(OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00"));
	}

	@Test
	@DisplayName("같은 최신 버전 재요청 → 최초 ID·시각·조건 원문 유지, 새 저장 없음 (나중 시각으로 호출해도)")
	void replay() {
		var first = saved(9001L, 3, LocalDateTime.parse("2026-11-27T01:00:00.000001"), "{\"original\":true}");
		given(acknowledgements.findByApplicationIdAndTermsVersion(APPLICATION, 3)).willReturn(Optional.of(first));
		var response = confirmAt(NOW.plusSeconds(600), 3);
		assertThat(response.conditionConfirmation().acknowledgementId()).isEqualTo(9001L);
		assertThat(response.conditionConfirmation().acknowledgedAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T10:00:00.000001+09:00"));
		assertThat(first.getConditionsPayload()).isEqualTo("{\"original\":true}");
		nothingSaved();
	}

	@Test
	@DisplayName("최초 지원 버전 = 최신 → 재확인 행 없이 200 대상: INITIAL_APPLICATION·true·재확인 ID/버전/시각 null")
	void initialLatest() {
		ReflectionTestUtils.setField(application, "appliedTermsVersion", 3);
		var info = confirm(3).conditionConfirmation();
		assertThat(info.source()).isEqualTo("INITIAL_APPLICATION");
		assertThat(info.confirmedTermsVersion()).isEqualTo(3);
		assertThat(info.latestTermsConfirmed()).isTrue();
		assertThat(info.acknowledgementId()).isNull();
		assertThat(info.acknowledgedTermsVersion()).isNull();
		assertThat(info.acknowledgedAt()).isNull();
		verify(acknowledgements, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("새 기록이 필요한데 약정 조건이 불완전 → 409 WORK_TERMS_UNAVAILABLE, 저장 없음")
	void incompleteTerms() {
		ReflectionTestUtils.setField(posting, "cropCode", " ");
		code(() -> confirm(3), 409, "WORK_TERMS_UNAVAILABLE");
		nothingSaved();
	}

	@Test
	@DisplayName("처리 순서: 역할 → 본인 소속 → 공고 잠금 → 일정 → 지원 → 확정 근무 → 기존 기록 → 시각 → 저장")
	void order() {
		var clock = mock(Clock.class);
		given(clock.instant()).willReturn(NOW);
		service(clock).confirm(WORKER, APPLICATION, new TermsVersionRequest(3));
		var order = inOrder(members, applications, postings, schedules, assignments, acknowledgements, clock);
		order.verify(members).findById(WORKER);
		order.verify(applications).findOwnedLocation(WORKER, APPLICATION);
		order.verify(postings).findByIdForUpdate(POSTING);
		order.verify(schedules).findByIdAndJobPosting_Id(SCHEDULE, POSTING);
		order.verify(applications).findScopedForUpdate(APPLICATION, SCHEDULE);
		order.verify(assignments).findByApplicationForUpdate(APPLICATION);
		order.verify(acknowledgements).findByApplicationIdAndTermsVersion(APPLICATION, 3);
		order.verify(clock).instant();
		order.verify(acknowledgements).saveAndFlush(any());
	}

	@Test
	@DisplayName("ID·버전 입력 오류 → 400 INVALID_REQUEST, 저장소 호출 없음")
	void invalid() {
		var s = service(clock(NOW));
		code(() -> s.confirm(0, APPLICATION, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
		code(() -> s.confirm(WORKER, 0, new TermsVersionRequest(3)), 400, "INVALID_REQUEST");
		code(() -> s.confirm(WORKER, APPLICATION, new TermsVersionRequest(null)), 400, "INVALID_REQUEST");
		code(() -> s.confirm(WORKER, APPLICATION, null), 400, "INVALID_REQUEST");
		verifyNoInteractions(members, applications, acknowledgements);
	}
}

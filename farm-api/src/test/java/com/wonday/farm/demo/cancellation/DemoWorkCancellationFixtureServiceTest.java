package com.wonday.farm.demo.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.demo.selection.DemoWorkSelectionFixtureService;
import com.wonday.farm.demo.selection.DemoWorkSelectionFixtureService.Case;
import com.wonday.farm.insurance.testsupport.InsuranceFixtures;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.dto.WorkSelectionResponse;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkSelectionService;
import com.wonday.farm.work.testsupport.SelectionFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// 실제 DemoWorkCancellationFixtureService, WORK-02 준비·선정 서비스와 저장소는 mock.
// 실제 DB의 시연 공고 표식·잠금·트랜잭션 롤백은 검증하지 않는다. 가상 데이터만 사용한다.
@DisplayName("farm-demo CHANGE-01·02 취소 시연 데이터 준비 (서비스, mock)")
class DemoWorkCancellationFixtureServiceTest {
	private static final long EMPLOYER = 10L, WORKER = 123L, P = 4201L;
	private final MemberRepository members = mock(MemberRepository.class);
	private final DemoWorkSelectionFixtureService fixtures = mock(DemoWorkSelectionFixtureService.class);
	private final WorkSelectionService selection = mock(WorkSelectionService.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkSelectionRepository assignments = mock(WorkSelectionRepository.class);
	private final WorkChangeRepository changes = mock(WorkChangeRepository.class);
	private final DemoWorkCancellationFixtureService service = new DemoWorkCancellationFixtureService(members, fixtures, selection,
		applications, schedules, assignments, changes);
	private final List<String> all = List.of("NORMAL", "CHANGED", "STALE_VERSION", "PAUSED", "CLOSED", "REMOVED", "STARTED",
		"CANCELLED", "EARLY_ENDED", "COMPLETED", "CAPACITY_FULL");
	private WorkSchedule schedule;

	private static Case item(String scenario, long posting, long index, Long assignmentId) {
		return new Case(scenario, "WORK-02 " + scenario, posting, 300 + index, 1000 + index, WORKER, "가상 근로자", EMPLOYER, "가상 고용주",
			LocalDate.parse("2026-12-10"), "OPEN", 2, 2, assignmentId == null ? "PENDING" : "SELECTED", assignmentId,
			assignmentId == null ? "PENDING" : "CONFIRMED");
	}
	private WorkAssignment assignment(long id) {
		return SelectionFixtures.assignment(id, schedule, LocalDateTime.parse("2026-11-20T00:00"));
	}
	private WorkChange change(long assignmentId, WorkChange.Type type, WorkChange.Reason reason, WorkChange.WeatherReason weather, String detail) {
		return InsuranceFixtures.change(9000 + assignmentId, assignmentId, type, reason, weather, detail,
			LocalDateTime.parse("2026-11-25T00:00"), LocalDateTime.parse("2026-11-25T00:00"));
	}

	@BeforeEach
	void ready() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(SelectionFixtures.member(EMPLOYER, MemberRole.EMPLOYER)));
		schedule = SelectionFixtures.schedule(SelectionFixtures.posting(1), WorkSchedule.Status.OPEN, 2);
		given(assignments.findByApplicationForUpdate(anyLong())).willReturn(Optional.empty());
		given(changes.findForUpdate(anyLong())).willReturn(Optional.empty());
		// 선정 서비스는 지원 ID → 확정 근무 ID(7000+지원 끝자리)를 반환한다.
		given(selection.select(eq(EMPLOYER), anyLong(), anyLong(), anyLong(), any())).willAnswer(invocation -> {
			long app = invocation.getArgument(3);
			long id = 7000 + app % 100;
			return new WorkSelectionService.Result(false, new WorkSelectionResponse(app, invocation.getArgument(2), WORKER, "SELECTED", id, 2,
				OffsetDateTime.parse("2026-11-28T15:00+09:00"), "CONFIRMED", null));
		});
		given(assignments.findById(anyLong())).willAnswer(invocation -> Optional.of(assignment(invocation.getArgument(0))));
		// STARTED 전용 경로: 어제 시작한 대기 지원
		var started = SelectionFixtures.application(2, "PENDING", true);
		ReflectionTestUtils.setField(started, "id", 1006L);
		given(applications.findScopedForUpdate(1006L, 306L)).willReturn(Optional.of(started));
		var past = SelectionFixtures.schedule(SelectionFixtures.posting(1), WorkSchedule.Status.OPEN, 2);
		ReflectionTestUtils.setField(past, "id", 306L);
		ReflectionTestUtils.setField(past, "plannedStartAt", LocalDateTime.parse("2026-11-27T09:00"));
		given(schedules.findByIdAndJobPosting_Id(306L, P)).willReturn(Optional.of(past));
		given(assignments.saveAndFlush(any())).willAnswer(invocation -> {
			WorkAssignment saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 7106L);
			return saved;
		});
	}
	private void prepared(boolean created, Long posting, List<Case> cases) {
		given(fixtures.prepare(EMPLOYER)).willReturn(new DemoWorkSelectionFixtureService.FixtureResponse(created, posting, cases));
	}
	private List<Case> freshCases() {
		var cases = new ArrayList<Case>();
		for (int i = 0; i < all.size(); i++) cases.add(item(all.get(i), P, i, null));
		return cases;
	}

	@Test
	@DisplayName("고용주 본인만 준비: 회원 없음·근로자·알 수 없는 역할은 WORK-02 준비 전에 거절")
	void employerOnly() {
		for (var member : new Optional[] {Optional.empty(), Optional.of(SelectionFixtures.member(EMPLOYER, MemberRole.WORKER)),
				Optional.of(SelectionFixtures.memberWithRole(EMPLOYER, "ADMIN"))}) {
			given(members.findById(EMPLOYER)).willReturn(member);
			assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(fixtures, selection, applications, schedules, assignments, changes);
	}

	@Test
	@DisplayName("신규 준비: 정상·다른 정상·일시중단은 선정 서비스로, 시작 경과는 과거 약정으로, 대상 외 사례는 제외")
	void freshPreparation() {
		prepared(true, P, freshCases());
		var result = service.prepare(EMPLOYER);
		assertThat(result.created()).isTrue();
		assertThat(result.jobPostingId()).isEqualTo(P);
		assertThat(result.cases()).extracting(DemoWorkCancellationFixtureService.FixtureCase::scenario)
			.containsExactly("NORMAL", "STALE_VERSION", "PAUSED", "STARTED", "CANCELLED", "EARLY_ENDED", "COMPLETED");
		// 선정 서비스 경유: 정상·다른 정상·일시중단 + (이번 mock에서는 기존 확정이 없는) 취소·조기 종료·완료 사례
		verify(selection).select(EMPLOYER, P, 300L, 1000L, new TermsVersionRequest(2));
		verify(selection).select(EMPLOYER, P, 302L, 1002L, new TermsVersionRequest(2));
		verify(selection).select(EMPLOYER, P, 303L, 1003L, new TermsVersionRequest(2));
		verify(selection, never()).select(anyLong(), anyLong(), eq(306L), anyLong(), any());
		for (long excluded : new long[] {1001L, 1004L, 1005L, 1010L}) {
			verify(selection, never()).select(anyLong(), anyLong(), anyLong(), eq(excluded), any());
			verify(assignments, never()).findByApplicationForUpdate(excluded);
		}
		var normal = result.cases().get(0);
		assertThat(normal.assignmentId()).isEqualTo(7000L);
		assertThat(normal.workStatus()).isEqualTo("CONFIRMED");
		assertThat(normal.jobPostingId()).isEqualTo(P);
		assertThat(normal.workScheduleId()).isEqualTo(300L);
		assertThat(normal.agreedStartAt()).isEqualTo(OffsetDateTime.parse("2026-12-10T09:00+09:00"));
		assertThat(normal.suggestedRequest()).isEqualTo(new WorkCancellationRequest("WEATHER", "RAIN", "강수로 작업을 취소합니다."));
		assertThat(normal.label()).isEqualTo("최초 취소·동일 재요청·다른 사유 409");
	}

	@Test
	@DisplayName("시작 경과 사례: 대기 지원을 선정하고 약정 시작 하루 전 확정 시각의 시연용 과거 약정을 저장")
	void startedScenario() {
		prepared(true, P, List.of(item("STARTED", P, 6, null)));
		var result = service.prepare(EMPLOYER);
		var captor = ArgumentCaptor.forClass(WorkAssignment.class);
		verify(assignments).saveAndFlush(captor.capture());
		var saved = captor.getValue();
		assertThat(saved.getAgreedStartAt()).isEqualTo(LocalDateTime.parse("2026-11-27T09:00"));
		assertThat(saved.getConfirmedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-26T00:00"));
		assertThat(saved.getAgreedTimeZone()).isEqualTo("GMT+9");
		assertThat(applications.findScopedForUpdate(1006L, 306L).orElseThrow().getStatus()).isEqualTo("SELECTED");
		var started = result.cases().get(0);
		assertThat(started.assignmentId()).isEqualTo(7106L);
		assertThat(started.workStatus()).isEqualTo("CONFIRMED");
		assertThat(started.agreedStartAt()).isEqualTo(OffsetDateTime.parse("2026-11-27T09:00+09:00"));
		verify(selection, never()).select(anyLong(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("시작 경과 사례: 지원이 대기·현재 회차가 아니거나 일정·지원이 없으면 503 (성공으로 표시하지 않음)")
	void startedFailures() {
		prepared(true, P, List.of(item("STARTED", P, 6, null)));
		var app = applications.findScopedForUpdate(1006L, 306L).orElseThrow();
		ReflectionTestUtils.setField(app, "status", "REJECTED");
		unavailable();
		ReflectionTestUtils.setField(app, "status", "PENDING");
		ReflectionTestUtils.setField(app, "current", false);
		unavailable();
		given(applications.findScopedForUpdate(1006L, 306L)).willReturn(Optional.empty());
		unavailable();
		verify(assignments, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("재사용: 기존 취소·조기 종료·완료·확정은 초기화하지 않고 상태 그대로 반환, 선정·저장 호출 없음")
	void reuseKeepsStates() {
		var cases = List.of(item("NORMAL", P, 0, 7000L), item("CANCELLED", P, 7, 7007L), item("EARLY_ENDED", P, 8, 7008L),
			item("COMPLETED", P, 9, 7009L));
		prepared(false, P, cases);
		given(assignments.findByApplicationForUpdate(1000L)).willReturn(Optional.of(assignment(7000L)));
		given(assignments.findByApplicationForUpdate(1007L)).willReturn(Optional.of(assignment(7007L)));
		given(assignments.findByApplicationForUpdate(1008L)).willReturn(Optional.of(assignment(7008L)));
		var completed = assignment(7009L);
		completed.complete(LocalDateTime.parse("2026-11-26T09:00"), EMPLOYER);
		given(assignments.findByApplicationForUpdate(1009L)).willReturn(Optional.of(completed));
		var cancelled = change(7007L, WorkChange.Type.CANCELLED, WorkChange.Reason.WEATHER, WorkChange.WeatherReason.RAIN, "[WORK-02 시연] 가상 기상 변경");
		var before = List.of(cancelled.getReasonCode(), cancelled.getReasonDetail(), cancelled.getSourceVersion(), cancelled.getUpdatedAt());
		given(changes.findForUpdate(7007L)).willReturn(Optional.of(cancelled));
		given(changes.findForUpdate(7008L)).willReturn(Optional.of(change(7008L, WorkChange.Type.EARLY_ENDED, WorkChange.Reason.WEATHER,
			WorkChange.WeatherReason.RAIN, "조기")));

		var result = service.prepare(EMPLOYER);
		assertThat(result.created()).isFalse();
		assertThat(result.cases()).extracting(DemoWorkCancellationFixtureService.FixtureCase::workStatus)
			.containsExactly("CONFIRMED", "CANCELLED", "EARLY_ENDED", "COMPLETED");
		assertThat(result.cases()).extracting(DemoWorkCancellationFixtureService.FixtureCase::assignmentId)
			.containsExactly(7000L, 7007L, 7008L, 7009L);
		verifyNoInteractions(selection);
		verify(assignments, never()).saveAndFlush(any());
		verify(changes, never()).saveAndFlush(any());
		verify(changes, never()).save(any());
		verify(changes, never()).delete(any());
		assertThat(List.of(cancelled.getReasonCode(), cancelled.getReasonDetail(), cancelled.getSourceVersion(), cancelled.getUpdatedAt()))
			.isEqualTo(before);
		assertThat(completed.getCompletedAtUtc()).isEqualTo(LocalDateTime.parse("2026-11-26T09:00"));
	}

	@Test
	@DisplayName("기존 취소의 suggestedRequest는 저장된 세 사유 필드와 일치, 그 외는 기본 WEATHER·RAIN 안내")
	void suggestedRequestMatchesStoredCancellation() {
		prepared(false, P, List.of(item("CANCELLED", P, 7, 7007L), item("EARLY_ENDED", P, 8, 7008L), item("NORMAL", P, 0, 7000L)));
		given(assignments.findByApplicationForUpdate(1007L)).willReturn(Optional.of(assignment(7007L)));
		given(assignments.findByApplicationForUpdate(1008L)).willReturn(Optional.of(assignment(7008L)));
		given(assignments.findByApplicationForUpdate(1000L)).willReturn(Optional.of(assignment(7000L)));
		given(changes.findForUpdate(7007L)).willReturn(Optional.of(change(7007L, WorkChange.Type.CANCELLED,
			WorkChange.Reason.FARM_CIRCUMSTANCES, null, "  저장된 설명  ")));
		given(changes.findForUpdate(7008L)).willReturn(Optional.of(change(7008L, WorkChange.Type.EARLY_ENDED,
			WorkChange.Reason.OTHER, null, null)));
		given(changes.findForUpdate(7000L)).willReturn(Optional.of(change(7000L, WorkChange.Type.CANCELLED,
			WorkChange.Reason.OTHER, null, null)));
		var result = service.prepare(EMPLOYER);
		assertThat(result.cases().get(0).suggestedRequest())
			.isEqualTo(new WorkCancellationRequest("FARM_CIRCUMSTANCES", null, "  저장된 설명  "));
		assertThat(result.cases().get(1).suggestedRequest()).isEqualTo(new WorkCancellationRequest("WEATHER", "RAIN", "강수로 작업을 취소합니다."));
		// 이전 준비 후 사용자가 취소한 정상 사례: 반복 준비가 CONFIRMED로 되돌리지 않고, 저장 사유를 안내한다.
		assertThat(result.cases().get(2).workStatus()).isEqualTo("CANCELLED");
		assertThat(result.cases().get(2).suggestedRequest()).isEqualTo(new WorkCancellationRequest("OTHER", null, null));
		verifyNoInteractions(selection);
	}

	@Test
	@DisplayName("preparedJobPostingId와 다른 공고 사례 제외, 자정 이후 날짜를 다시 조회하지 않고 준비 응답만 사용")
	void onlyPreparedPostingAndNoDateReread() {
		var cases = new ArrayList<Case>();
		cases.add(item("NORMAL", 3900L, 20, null));
		cases.add(item("CANCELLED", 3900L, 27, null));
		cases.addAll(freshCases());
		prepared(true, P, cases);
		var result = service.prepare(EMPLOYER);
		assertThat(result.cases()).allMatch(c -> c.jobPostingId() == P).hasSize(7);
		verify(selection, never()).select(anyLong(), eq(3900L), anyLong(), anyLong(), any());
		verify(assignments, never()).findByApplicationForUpdate(1020L);
		verify(fixtures, times(1)).prepare(EMPLOYER);
		verify(fixtures, never()).list(anyLong());
	}

	@Test
	@DisplayName("준비 응답에 공고 ID가 없으면 503, 선정 실패·DB 실패는 예외 전파")
	void failuresPropagate() {
		prepared(false, null, freshCases());
		unavailable();
		verifyNoInteractions(selection);

		prepared(true, P, List.of(item("NORMAL", P, 0, null)));
		given(selection.select(eq(EMPLOYER), anyLong(), anyLong(), anyLong(), any()))
			.willThrow(WorkSelectionException.conflict("CAPACITY_FULL", "확정 인원이 정원을 채웠습니다."));
		assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOfSatisfying(WorkSelectionException.class,
			e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

		given(fixtures.prepare(EMPLOYER)).willThrow(new DataAccessResourceFailureException("down"));
		assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	@DisplayName("선정 후 확정 근무를 찾지 못하면 503")
	void selectedAssignmentMissing() {
		prepared(true, P, List.of(item("NORMAL", P, 0, null)));
		given(selection.select(eq(EMPLOYER), anyLong(), anyLong(), anyLong(), any())).willReturn(new WorkSelectionService.Result(false,
			new WorkSelectionResponse(1000L, 300L, WORKER, "SELECTED", 7999L, 2, OffsetDateTime.parse("2026-11-28T15:00+09:00"), "CONFIRMED", null)));
		given(assignments.findById(7999L)).willReturn(Optional.empty());
		unavailable();
	}

	@Test
	@DisplayName("farm-demo 프로필 전용 선언 (API·서비스·오류 처리기)")
	void profile() {
		for (var type : List.of(DemoWorkCancellationApi.class, DemoWorkCancellationFixtureService.class, DemoWorkCancellationErrorHandler.class)) {
			assertThat(type.getAnnotation(Profile.class)).as(type.getSimpleName()).isNotNull();
			assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
		}
	}

	@Test
	@DisplayName("시연 준비는 회원 저장·비밀번호 변경 경로를 갖지 않음 (의존성 선언)")
	void noMemberWrites() {
		prepared(true, P, freshCases());
		service.prepare(EMPLOYER);
		verify(members).findById(EMPLOYER);
		org.mockito.Mockito.verifyNoMoreInteractions(members);
		var fields = java.util.Arrays.stream(DemoWorkCancellationFixtureService.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()).toList();
		assertThat(fields).doesNotContain("PasswordEncoder");
	}

	private void unavailable() {
		assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOfSatisfying(WorkCancellationException.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE");
		});
	}
}

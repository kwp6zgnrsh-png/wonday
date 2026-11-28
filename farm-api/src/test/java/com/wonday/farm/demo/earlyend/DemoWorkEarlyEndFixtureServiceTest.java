package com.wonday.farm.demo.earlyend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.wonday.farm.application.entity.ApplicationRequestLock;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkCompletionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkCompletionService;
import com.wonday.farm.work.testsupport.SelectionFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

// 실제 DemoWorkEarlyEndFixtureService, 저장소·기록기·완료 서비스는 mock, Clock은 고정 또는 mock.
// 실제 DB의 시연 표식 조회 SQL·근로자 잠금·트랜잭션 롤백은 검증하지 않는다. 가상 데이터만 사용한다.
@DisplayName("farm-demo CHANGE-03 조기 종료 시연 데이터 준비 (서비스, mock)")
class DemoWorkEarlyEndFixtureServiceTest {
	private static final long EMPLOYER = 10L, WORKER = 123L, P = 4201L;
	// UTC로는 11-28이지만 GMT+9로는 2026-11-29 00:30:00.123456789 (자정 직후)
	private static final Instant NOW = Instant.parse("2026-11-28T15:30:00.123456789Z");
	private static final LocalDate TODAY = LocalDate.parse("2026-11-29");
	private static final String MARKER = "[CHANGE-03 시연·고용주 10·근로자 123·2026-11-29]";
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);
	private static final List<String> TYPES = List.of("NORMAL", "LATE_ALLOWED", "NOT_STARTED", "DEADLINE_PASSED", "CANCELLED", "EARLY_ENDED", "COMPLETED");
	private static final Map<String, LocalDate> DATES = Map.of("NORMAL", TODAY, "LATE_ALLOWED", TODAY.minusDays(1),
		"NOT_STARTED", TODAY.plusDays(1), "DEADLINE_PASSED", TODAY.minusDays(2), "CANCELLED", TODAY.plusDays(2),
		"EARLY_ENDED", TODAY.minusDays(3), "COMPLETED", TODAY.minusDays(4));

	private final MemberRepository members = mock(MemberRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final DemoJobApplicationRepository lookup = mock(DemoJobApplicationRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final WorkSelectionRepository assignments = mock(WorkSelectionRepository.class);
	private final WorkChangeRecorder recorder = mock(WorkChangeRecorder.class);
	private final WorkCompletionService completion = mock(WorkCompletionService.class);
	private final DemoWorkEarlyEndRepository fixtures = mock(DemoWorkEarlyEndRepository.class);
	private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private final List<JobApplication> savedApps = new ArrayList<>();
	private final List<WorkAssignment> savedAssignments = new ArrayList<>();

	private DemoWorkEarlyEndFixtureService service() {
		return new DemoWorkEarlyEndFixtureService(members, locks, lookup, postings, schedules, applications, assignments, recorder,
			completion, fixtures, clock);
	}

	@BeforeEach
	void ready() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(SelectionFixtures.member(EMPLOYER, MemberRole.EMPLOYER)));
		given(members.findByLoginId("demo-worker")).willReturn(Optional.of(SelectionFixtures.member(WORKER, MemberRole.WORKER)));
		given(locks.lockWorker(WORKER)).willReturn(Optional.of(mock(ApplicationRequestLock.class)));
		given(lookup.findFixturePosting(eq(EMPLOYER), anyString())).willReturn(Optional.empty());
		given(postings.saveAndFlush(any())).willAnswer(invocation -> {
			JobPosting posting = invocation.getArgument(0);
			ReflectionTestUtils.setField(posting, "id", P);
			return posting;
		});
		var scheduleIds = new AtomicLong(300);
		given(schedules.saveAllAndFlush(any())).willAnswer(invocation -> {
			List<WorkSchedule> list = invocation.getArgument(0);
			for (var schedule : list) ReflectionTestUtils.setField(schedule, "id", scheduleIds.incrementAndGet());
			return list;
		});
		var appIds = new AtomicLong(1000);
		given(applications.saveAndFlush(any())).willAnswer(invocation -> {
			JobApplication app = invocation.getArgument(0);
			ReflectionTestUtils.setField(app, "id", appIds.incrementAndGet());
			savedApps.add(app);
			return app;
		});
		var assignmentIds = new AtomicLong(7000);
		given(assignments.saveAndFlush(any())).willAnswer(invocation -> {
			WorkAssignment assignment = invocation.getArgument(0);
			ReflectionTestUtils.setField(assignment, "id", assignmentIds.incrementAndGet());
			savedAssignments.add(assignment);
			return assignment;
		});
	}

	// DB 조회 결과 흉내. 정상·늦은 입력은 00:00~23:59:59.999999, 나머지 09:00~17:00 (모두 현지 GMT+9)
	private static DemoWorkEarlyEndRepository.Row row(String type, String changeType, LocalDateTime effectiveUtc, String reason,
			String weather, String detail, LocalDateTime completedAt) {
		var date = DATES.get(type);
		boolean whole = type.equals("NORMAL") || type.equals("LATE_ALLOWED");
		int index = TYPES.indexOf(type);
		var values = new HashMap<String, Object>();
		values.put("getAssignmentId", 7001L + index);
		values.put("getWorkScheduleId", 301L + index);
		values.put("getWorkDate", date);
		values.put("getTimeZone", "GMT+9");
		values.put("getStartAt", whole ? date.atStartOfDay() : date.atTime(9, 0));
		values.put("getEndAt", whole ? date.atTime(23, 59, 59, 999_999_000) : date.atTime(17, 0));
		values.put("getCompletedAt", completedAt);
		values.put("getChangeType", changeType);
		values.put("getEffectiveAt", effectiveUtc);
		values.put("getReasonCode", reason);
		values.put("getWeatherReasonCode", weather);
		values.put("getReasonDetail", detail);
		return (DemoWorkEarlyEndRepository.Row) Proxy.newProxyInstance(DemoWorkEarlyEndRepository.Row.class.getClassLoader(),
			new Class<?>[] {DemoWorkEarlyEndRepository.Row.class}, (p, m, a) -> m.getName().equals("toString") ? "Row" + values : values.get(m.getName()));
	}
	private static final LocalDateTime EARLY_UTC = DATES.get("EARLY_ENDED").atTime(5, 0); // 14:00 GMT+9
	private static List<DemoWorkEarlyEndRepository.Row> freshRows() {
		return new ArrayList<>(List.of(row("NORMAL", null, null, null, null, null, null), row("LATE_ALLOWED", null, null, null, null, null, null),
			row("NOT_STARTED", null, null, null, null, null, null), row("DEADLINE_PASSED", null, null, null, null, null, null),
			row("CANCELLED", "CANCELLED", DATES.get("CANCELLED").atStartOfDay(), "WEATHER", "RAIN", "[CHANGE-03 시연] 기존 가상 기상 변경", null),
			row("EARLY_ENDED", "EARLY_ENDED", EARLY_UTC, "WEATHER", "RAIN", "[CHANGE-03 시연] 기존 가상 기상 변경", null),
			row("COMPLETED", null, null, null, null, null, DATES.get("COMPLETED").atTime(9, 0))));
	}
	private Map<String, DemoWorkEarlyEndFixtureService.FixtureCase> byScenario(DemoWorkEarlyEndFixtureService.FixtureResponse response) {
		var map = new HashMap<String, DemoWorkEarlyEndFixtureService.FixtureCase>();
		for (var item : response.cases()) map.put(item.scenario(), item);
		return map;
	}

	@Test
	@DisplayName("고용주 본인만 준비: 회원 없음·근로자·알 수 없는 역할은 잠금·조회 전에 거절")
	void employerOnly() {
		for (var member : List.of(Optional.<com.wonday.farm.member.Member>empty(), Optional.of(SelectionFixtures.member(EMPLOYER, MemberRole.WORKER)),
				Optional.of(SelectionFixtures.memberWithRole(EMPLOYER, "ADMIN")))) {
			given(members.findById(EMPLOYER)).willReturn(member);
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOf(AccessDeniedException.class);
		}
		verifyNoInteractions(locks, lookup, postings, schedules, applications, assignments, recorder, completion, fixtures);
	}

	@Test
	@DisplayName("demo-worker 없음·역할 불일치는 409 DEMO_ACCOUNT_UNAVAILABLE, 잠금 없음")
	void demoWorkerUnavailable() {
		for (var worker : List.of(Optional.<com.wonday.farm.member.Member>empty(), Optional.of(SelectionFixtures.member(WORKER, MemberRole.EMPLOYER)),
				Optional.of(SelectionFixtures.memberWithRole(WORKER, "ADMIN")))) {
			given(members.findByLoginId("demo-worker")).willReturn(worker);
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> {
				assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.code()).isEqualTo("DEMO_ACCOUNT_UNAVAILABLE");
			});
		}
		verifyNoInteractions(locks, lookup, postings);
	}

	@Test
	@DisplayName("근로자 요청 잠금 실패는 503, 조회·생성 없음")
	void lockUnavailable() {
		given(locks.lockWorker(WORKER)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkEarlyEndException.class,
			e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
		verifyNoInteractions(lookup, postings, schedules, applications, assignments, fixtures);
	}

	@Test
	@DisplayName("신규 준비 201용 응답: 잠금 → 시계 1회 → 표식 조회 → 공고·일정·지원·약정 생성, 기존 기록 경계 호출")
	void createsFixture() {
		var spyClock = mock(Clock.class);
		given(spyClock.instant()).willReturn(NOW);
		clock = spyClock;
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(freshRows());
		var result = service().prepare(EMPLOYER);

		assertThat(result.created()).isTrue();
		assertThat(result.jobPostingId()).isEqualTo(P);
		var order = inOrder(members, locks, spyClock, lookup, postings, schedules, fixtures);
		order.verify(members).findById(EMPLOYER);
		order.verify(members).findByLoginId("demo-worker");
		order.verify(locks).ensureExists(WORKER);
		order.verify(locks).lockWorker(WORKER);
		order.verify(spyClock).instant();
		order.verify(lookup).findFixturePosting(EMPLOYER, MARKER);
		order.verify(postings).saveAndFlush(any());
		order.verify(schedules).saveAllAndFlush(any());
		order.verify(fixtures).findCases(P, EMPLOYER, WORKER, MARKER);
		verify(spyClock, times(1)).instant();

		var posting = ArgumentCaptor.forClass(JobPosting.class);
		verify(postings).saveAndFlush(posting.capture());
		assertThat(posting.getValue().getTitle()).isEqualTo(MARKER);
		assertThat(posting.getValue().getTimeZone()).isEqualTo("GMT+9");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<WorkSchedule>> list = ArgumentCaptor.forClass(List.class);
		verify(schedules).saveAllAndFlush(list.capture());
		var saved = list.getValue();
		assertThat(saved).hasSize(7);
		for (int i = 0; i < 7; i++) {
			var type = TYPES.get(i);
			var schedule = saved.get(i);
			assertThat(schedule.getWorkDate()).as(type).isEqualTo(DATES.get(type));
			if (i < 2) {
				assertThat(schedule.getPlannedStartAt()).as(type).isEqualTo(DATES.get(type).atStartOfDay());
				assertThat(schedule.getPlannedEndAt()).as(type).isEqualTo(DATES.get(type).atTime(23, 59, 59, 999_999_000));
			} else {
				assertThat(schedule.getPlannedStartAt()).as(type).isEqualTo(DATES.get(type).atTime(9, 0));
				assertThat(schedule.getPlannedEndAt()).as(type).isEqualTo(DATES.get(type).atTime(17, 0));
			}
			assertThat(schedule.getJobPosting()).isSameAs(posting.getValue());
		}
		assertThat(savedApps).hasSize(7).allSatisfy(app -> {
			assertThat(app.getWorkerId()).isEqualTo(WORKER);
			assertThat(app.getStatus()).isEqualTo("SELECTED");
		});
		assertThat(savedAssignments).hasSize(7);
		for (int i = 0; i < 7; i++) {
			var start = saved.get(i).getPlannedStartAt().toInstant(KST);
			assertThat(savedAssignments.get(i).getConfirmedAtUtc()).isEqualTo(LocalDateTime.ofInstant(start.minusSeconds(86400), ZoneOffset.UTC));
			assertThat(savedAssignments.get(i).getApplicationId()).isEqualTo(savedApps.get(i).getId());
			assertThat(savedAssignments.get(i).getAgreedStartAt()).isEqualTo(saved.get(i).getPlannedStartAt());
		}
		// 취소(7005)·조기 종료(7006)만 기존 저장 경계, 완료(7007)만 완료 서비스
		verify(recorder).recordValidatedChange(EMPLOYER, 7005L, WorkChange.Type.CANCELLED, null, WorkChange.Reason.WEATHER,
			WorkChange.WeatherReason.RAIN, "[CHANGE-03 시연] 기존 가상 기상 변경");
		verify(recorder).recordValidatedChange(EMPLOYER, 7006L, WorkChange.Type.EARLY_ENDED,
			OffsetDateTime.parse("2026-11-26T14:00+09:00"), WorkChange.Reason.WEATHER, WorkChange.WeatherReason.RAIN, "[CHANGE-03 시연] 기존 가상 기상 변경");
		verifyNoMoreInteractions(recorder);
		verify(completion).complete(EMPLOYER, 7007L);
		verifyNoMoreInteractions(completion);
	}

	@Test
	@DisplayName("재사용: 기존 공고 200용 응답, 생성·기록·완료 호출 없음, 조회 범위는 공고·소유자·근로자·표식")
	void reusesExisting() {
		given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(P));
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(freshRows());
		var result = service().prepare(EMPLOYER);
		assertThat(result.created()).isFalse();
		assertThat(result.jobPostingId()).isEqualTo(P);
		assertThat(result.cases()).hasSize(7);
		verify(fixtures).findCases(P, EMPLOYER, WORKER, MARKER);
		verifyNoInteractions(postings, schedules, applications, assignments, recorder, completion);
	}

	@Test
	@DisplayName("7개 사례 연결: 근무일 매핑 분류(조회 순서 무관), assignmentId·약정·마감·상태·suggestedRequest")
	void caseLinks() {
		given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(P));
		var rows = freshRows();
		Collections.reverse(rows);
		Collections.swap(rows, 1, 4);
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(rows);
		var result = service().prepare(EMPLOYER);
		assertThat(result.cases()).extracting(DemoWorkEarlyEndFixtureService.FixtureCase::scenario).containsExactlyElementsOf(TYPES);
		var cases = byScenario(result);
		for (int i = 0; i < 7; i++) {
			var item = cases.get(TYPES.get(i));
			assertThat(item.assignmentId()).as(item.scenario()).isEqualTo(7001L + i);
			assertThat(item.workScheduleId()).isEqualTo(301L + i);
			assertThat(item.agreedStartAt().getOffset()).isEqualTo(KST);
			assertThat(item.recordingDeadline()).isEqualTo(item.agreedEndAt().plusHours(24));
			assertThat(item.label()).isNotBlank();
		}
		assertThat(cases.get("NORMAL").workStatus()).isEqualTo("CONFIRMED");
		assertThat(cases.get("CANCELLED").workStatus()).isEqualTo("CANCELLED");
		assertThat(cases.get("EARLY_ENDED").workStatus()).isEqualTo("EARLY_ENDED");
		assertThat(cases.get("COMPLETED").workStatus()).isEqualTo("COMPLETED");
		assertThat(cases.get("NORMAL").agreedStartAt()).isEqualTo(OffsetDateTime.parse("2026-11-29T00:00+09:00"));
		assertThat(cases.get("NORMAL").agreedEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-29T23:59:59.999999+09:00"));
		assertThat(cases.get("DEADLINE_PASSED").recordingDeadline()).isEqualTo(OffsetDateTime.parse("2026-11-28T17:00+09:00"));
		assertThat(cases.get("LATE_ALLOWED").recordingDeadline()).isEqualTo(OffsetDateTime.parse("2026-11-29T23:59:59.999999+09:00"));

		var basis = NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		// 정상: 기준 시각 1분 전(마이크로초 정밀도), 약정 범위 안, 기준 시각 이하
		var normal = cases.get("NORMAL").suggestedRequest();
		assertThat(normal.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-29T00:29:00.123456+09:00"));
		assertThat(normal.actualEndAt().getNano() % 1000).isZero();
		assertThat(normal).extracting(WorkEarlyEndRequest::reasonCode, WorkEarlyEndRequest::weatherReasonCode, WorkEarlyEndRequest::reasonDetail)
			.containsExactly("WEATHER", "RAIN", "기상으로 작업을 조기 종료합니다.");
		for (var type : List.of("NORMAL", "LATE_ALLOWED", "DEADLINE_PASSED", "COMPLETED")) {
			var item = cases.get(type);
			var actual = item.suggestedRequest().actualEndAt().toInstant();
			assertThat(actual).as(type).isAfterOrEqualTo(item.agreedStartAt().toInstant()).isBefore(item.agreedEndAt().toInstant());
			assertThat(actual).as(type).isBeforeOrEqualTo(basis);
		}
		assertThat(cases.get("LATE_ALLOWED").suggestedRequest().actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-28T22:59:59.999999+09:00"));
		assertThat(cases.get("NOT_STARTED").suggestedRequest().actualEndAt()).isEqualTo(cases.get("NOT_STARTED").agreedStartAt());
		assertThat(cases.get("CANCELLED").suggestedRequest().actualEndAt()).isEqualTo(cases.get("CANCELLED").agreedStartAt());
		// 기존 조기 종료: 저장된 시점·사유 그대로
		var early = cases.get("EARLY_ENDED").suggestedRequest();
		assertThat(early.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-11-26T14:00+09:00"));
		assertThat(early.actualEndAt().getOffset()).isEqualTo(KST);
		assertThat(early).extracting(WorkEarlyEndRequest::reasonCode, WorkEarlyEndRequest::weatherReasonCode, WorkEarlyEndRequest::reasonDetail)
			.containsExactly("WEATHER", "RAIN", "[CHANGE-03 시연] 기존 가상 기상 변경");
	}

	@Test
	@DisplayName("자정 직후 기준 시각: 정상 기본 종료는 약정 시작으로 보정")
	void normalSuggestionClampedToStart() {
		clock = Clock.fixed(Instant.parse("2026-11-28T15:00:30.5Z"), ZoneOffset.UTC); // GMT+9 2026-11-29 00:00:30.5
		given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(P));
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(freshRows());
		var normal = byScenario(service().prepare(EMPLOYER)).get("NORMAL");
		assertThat(normal.suggestedRequest().actualEndAt()).isEqualTo(normal.agreedStartAt());
	}

	@Test
	@DisplayName("반복 준비는 사용자가 기록한 조기 종료·취소·완료를 초기화하지 않고 저장 값으로 안내")
	void repeatedPreparationKeepsRecordedStates() {
		given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(P));
		var rows = freshRows();
		var normalUtc = LocalDateTime.parse("2026-11-28T15:20:00.000001");
		rows.set(0, row("NORMAL", "EARLY_ENDED", normalUtc, "FARM_CIRCUMSTANCES", null, "  농장 사정  ", null));
		rows.set(1, row("LATE_ALLOWED", null, null, null, null, null, DATES.get("LATE_ALLOWED").atTime(20, 0)));
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(rows);
		var cases = byScenario(service().prepare(EMPLOYER));
		var normal = cases.get("NORMAL");
		assertThat(normal.workStatus()).isEqualTo("EARLY_ENDED");
		assertThat(normal.suggestedRequest()).isEqualTo(new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-29T00:20:00.000001+09:00"),
			"FARM_CIRCUMSTANCES", null, "  농장 사정  "));
		assertThat(cases.get("LATE_ALLOWED").workStatus()).isEqualTo("COMPLETED");
		verifyNoInteractions(recorder, completion, assignments, applications);
	}

	@Test
	@DisplayName("근무일이 매핑되지 않는 행은 503 (성공으로 표시하지 않음)")
	void unknownDateIs503() {
		given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(P));
		var rows = freshRows();
		var values = new HashMap<String, Object>(Map.of("getWorkDate", TODAY.plusDays(30), "getTimeZone", "GMT+9"));
		rows.add((DemoWorkEarlyEndRepository.Row) Proxy.newProxyInstance(DemoWorkEarlyEndRepository.Row.class.getClassLoader(),
			new Class<?>[] {DemoWorkEarlyEndRepository.Row.class}, (p, m, a) -> values.get(m.getName())));
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(rows);
		assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkEarlyEndException.class,
			e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	@DisplayName("기록 경계·완료 서비스·저장 실패는 예외 전파")
	void failuresPropagate() {
		given(fixtures.findCases(anyLong(), anyLong(), anyLong(), anyString())).willReturn(freshRows());
		willThrow(new DataAccessResourceFailureException("down")).given(recorder)
			.recordValidatedChange(anyLong(), anyLong(), any(), isNull(), any(), any(), anyString());
		assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOf(DataAccessResourceFailureException.class);

		org.mockito.Mockito.reset(recorder);
		willThrow(WorkCompletionException.unavailable()).given(completion).complete(anyLong(), anyLong());
		assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOf(WorkCompletionException.class);

		willThrow(new DataAccessResourceFailureException("down")).given(postings).saveAndFlush(any());
		assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOf(DataAccessResourceFailureException.class);
	}

	@Test
	@DisplayName("회원 쓰기·비밀번호 변경 경로 없음, 생성자 의존성")
	void dependencies() {
		given(fixtures.findCases(P, EMPLOYER, WORKER, MARKER)).willReturn(freshRows());
		service().prepare(EMPLOYER);
		verify(members).findById(EMPLOYER);
		verify(members).findByLoginId("demo-worker");
		verifyNoMoreInteractions(members);
		var ctor = DemoWorkEarlyEndFixtureService.class.getConstructors();
		assertThat(ctor).hasSize(1);
		assertThat(ctor[0].getParameterTypes()).containsExactly(MemberRepository.class, ApplicationRequestLockRepository.class,
			DemoJobApplicationRepository.class, JobPostingRepository.class, WorkScheduleRepository.class, JobApplicationRepository.class,
			WorkSelectionRepository.class, WorkChangeRecorder.class, WorkCompletionService.class, DemoWorkEarlyEndRepository.class, Clock.class);
		assertThat(ctor[0].getParameters()[10].getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class).value())
			.isEqualTo("insuranceChangeClock");
	}

	@Test
	@DisplayName("farm-demo 프로필 선언(API·서비스·오류 처리기·저장소), 조회 SQL의 범위 조건 선언")
	void profileAndQuery() throws Exception {
		for (var type : List.of(DemoWorkEarlyEndApi.class, DemoWorkEarlyEndFixtureService.class, DemoWorkEarlyEndErrorHandler.class,
				DemoWorkEarlyEndRepository.class)) {
			assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
		}
		String sql = DemoWorkEarlyEndRepository.class.getMethod("findCases", long.class, long.class, long.class, String.class)
			.getAnnotation(Query.class).value();
		assertThat(sql.replaceAll("\\s+", " ")).contains("posting.id = :postingId and posting.employer_id = :employerId",
			"and app.worker_id = :workerId and posting.title = :marker");
	}
}

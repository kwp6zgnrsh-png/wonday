package com.wonday.farm.demo.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.demo.applicant.DemoWorkApplicantFixtureRepository;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

// 실제 준비 서비스 + mock 저장소·PasswordEncoder·변경/완료 서비스, 고정 Clock. 실제 데이터는 만들지 않는다.
// 실제 DB 원자성(중간 실패 롤백)·근로자 잠금에 의한 동시 준비 직렬화·완료/변경 서비스 내부 검증은 mock으로 증명하지 못한다.
@DisplayName("farm-demo WORK-02 시연 데이터 준비·조회 서비스 (mock·고정 Clock)")
class DemoWorkSelectionFixtureServiceTest {
	private static final long EMPLOYER = 10L, WORKER = 123L;
	private static final Instant NOW = Instant.parse("2026-11-28T06:00:00.123456789Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-28T06:00:00.123456");
	private static final LocalDate TODAY = LocalDate.parse("2026-11-28");
	private static final String MARKER = "[WORK-02 시연·고용주 10·근로자 123·2026-11-28]";
	private static final List<String> TYPES = List.of("NORMAL", "CHANGED", "STALE_VERSION", "PAUSED", "CLOSED", "REMOVED",
		"STARTED", "CANCELLED", "EARLY_ENDED", "COMPLETED", "CAPACITY_FULL");

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkScheduleRevisionRepository revisions = mock(WorkScheduleRevisionRepository.class);
	private final JobApplicationRepository applications = mock(JobApplicationRepository.class);
	private final WorkSelectionRepository assignments = mock(WorkSelectionRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final DemoJobApplicationRepository lookup = mock(DemoJobApplicationRepository.class);
	private final DemoWorkApplicantFixtureRepository statuses = mock(DemoWorkApplicantFixtureRepository.class);
	private final DemoWorkSelectionRepository fixtures = mock(DemoWorkSelectionRepository.class);
	private final WorkChangeRecorder changes = mock(WorkChangeRecorder.class);
	private final WorkCompletionService completion = mock(WorkCompletionService.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private Member employer, worker;
	private final List<JobApplication> savedApplications = new ArrayList<>();
	private final List<WorkAssignment> savedAssignments = new ArrayList<>();
	private final List<WorkScheduleRevision> savedRevisions = new ArrayList<>();
	private final Map<Long, Integer> versionAtApplication = new HashMap<>();

	private DemoWorkSelectionFixtureService service(Clock clock) {
		return new DemoWorkSelectionFixtureService(members, postings, schedules, revisions, applications, assignments, locks, lookup,
			statuses, fixtures, changes, completion, passwords, clock);
	}
	private DemoWorkSelectionFixtureService service() { return service(Clock.fixed(NOW, ZoneOffset.UTC)); }
	private static Member member(long id, String login, MemberRole role) {
		var m = new Member(login, "existing-hash-" + id, "기존 회원 " + id, "01099990000", role);
		ReflectionTestUtils.setField(m, "id", id);
		return m;
	}
	private static DemoWorkSelectionRepository.CaseRow caseRow(long postingId, long scheduleId, long applicationId, String status, String workStatus) {
		Map<String, Object> v = new HashMap<>();
		v.put("jobPostingId", postingId); v.put("workScheduleId", scheduleId); v.put("applicationId", applicationId);
		v.put("workerId", WORKER); v.put("workerName", "기존 회원 123"); v.put("employerId", EMPLOYER); v.put("employerName", "기존 회원 10");
		v.put("workDate", TODAY.plusDays(7)); v.put("recruitmentStatus", "OPEN"); v.put("termsVersion", 1);
		v.put("appliedTermsVersion", 1); v.put("applicationStatus", status); v.put("workStatus", workStatus);
		return (DemoWorkSelectionRepository.CaseRow) Proxy.newProxyInstance(DemoWorkSelectionRepository.CaseRow.class.getClassLoader(),
			new Class<?>[] {DemoWorkSelectionRepository.CaseRow.class}, (p, m, a) -> m.getName().equals("toString") ? "CaseRow" + v
				: v.get(Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4)));
	}
	private static List<DemoWorkSelectionRepository.CaseRow> rows(long postingId, int count, long firstApp) {
		var list = new ArrayList<DemoWorkSelectionRepository.CaseRow>();
		for (int i = 0; i < count; i++) list.add(caseRow(postingId, 300 + i, firstApp + i, "PENDING", "PENDING"));
		return list;
	}

	@BeforeEach
	void ready() {
		employer = member(EMPLOYER, "demo-employer", MemberRole.EMPLOYER);
		worker = member(WORKER, "demo-worker", MemberRole.WORKER);
		given(members.findById(EMPLOYER)).willReturn(Optional.of(employer));
		given(members.findById(WORKER)).willReturn(Optional.of(worker));
		given(members.findByLoginId("demo-worker")).willReturn(Optional.of(worker));
		given(members.findByLoginId("demo-employer")).willReturn(Optional.of(employer));
		given(locks.lockWorker(WORKER)).willReturn(Optional.of(mock(com.wonday.farm.application.entity.ApplicationRequestLock.class)));
		given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.empty());
		given(fixtures.findCases(anyLong(), any())).willReturn(List.of());
		var ids = new AtomicLong(1000);
		given(postings.saveAndFlush(any())).willAnswer(c -> { JobPosting p = c.getArgument(0); ReflectionTestUtils.setField(p, "id", 4201L); return p; });
		given(schedules.saveAllAndFlush(any())).willAnswer(c -> {
			List<WorkSchedule> list = new ArrayList<>(c.<List<WorkSchedule>>getArgument(0));
			for (int i = 0; i < list.size(); i++) ReflectionTestUtils.setField(list.get(i), "id", 301L + i);
			return list;
		});
		given(schedules.saveAndFlush(any())).willAnswer(c -> c.getArgument(0));
		given(revisions.save(any())).willAnswer(c -> { savedRevisions.add(c.getArgument(0)); return c.getArgument(0); });
		given(applications.saveAndFlush(any())).willAnswer(c -> {
			JobApplication a = c.getArgument(0); long id = ids.incrementAndGet();
			ReflectionTestUtils.setField(a, "id", id); savedApplications.add(a);
			versionAtApplication.put(id, a.getAppliedTermsVersion());
			return a;
		});
		given(assignments.saveAndFlush(any())).willAnswer(c -> {
			WorkAssignment a = c.getArgument(0); ReflectionTestUtils.setField(a, "id", 7000L + savedAssignments.size()); savedAssignments.add(a); return a;
		});
		given(members.saveAndFlush(any())).willAnswer(c -> { Member m = c.getArgument(0); ReflectionTestUtils.setField(m, "id", 900L); return m; });
		given(passwords.encode(anyString())).willReturn("{bcrypt}ENCODED-RANDOM");
		given(statuses.setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any())).willReturn(1);
	}

	@Test
	@DisplayName("선언: API·서비스·저장소·오류 처리기 모두 farm-demo 프로필 전용")
	void profile() {
		for (var type : List.of(DemoWorkSelectionApi.class, DemoWorkSelectionFixtureService.class, DemoWorkSelectionRepository.class,
				DemoWorkSelectionErrorHandler.class)) {
			assertThat(type.getAnnotation(Profile.class)).as(type.getSimpleName()).isNotNull();
			assertThat(type.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
		}
	}

	@Nested
	@DisplayName("역할·상대 계정·잠금·재사용")
	class Access {
		@Test
		@DisplayName("회원 없음·알 수 없는 역할 → AccessDenied (준비·조회 모두), 잠금·저장 없음")
		void actor() {
			given(members.findById(55L)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service().prepare(55L)).isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service().list(55L)).isInstanceOf(AccessDeniedException.class);
			var admin = member(56L, "admin", MemberRole.WORKER);
			ReflectionTestUtils.setField(admin, "role", "ADMIN");
			given(members.findById(56L)).willReturn(Optional.of(admin));
			assertThatThrownBy(() -> service().prepare(56L)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(locks, postings, fixtures);
		}

		@Test
		@DisplayName("고용주로 준비 → 상대 demo-worker, 근로자로 준비 → 상대 demo-employer (같은 표식·근로자 잠금)")
		void counterpart() {
			service().prepare(EMPLOYER);
			verify(members).findByLoginId("demo-worker");
			verify(lookup).findFixturePosting(EMPLOYER, MARKER);
			verify(locks).lockWorker(WORKER);
			service().prepare(WORKER);
			verify(members).findByLoginId("demo-employer");
			verify(lookup, times(2)).findFixturePosting(EMPLOYER, MARKER);
			verify(locks, times(2)).lockWorker(WORKER);
		}

		@Test
		@DisplayName("상대 계정 없음·역할 불일치 → 409 DEMO_ACCOUNT_UNAVAILABLE, 잠금·저장 없음")
		void counterpartMissing() {
			given(members.findByLoginId("demo-worker")).willReturn(Optional.empty());
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkSelectionException.class,
				e -> assertThat(e.code()).isEqualTo("DEMO_ACCOUNT_UNAVAILABLE"));
			given(members.findByLoginId("demo-employer")).willReturn(Optional.of(member(77L, "demo-employer", MemberRole.WORKER)));
			assertThatThrownBy(() -> service().prepare(WORKER)).isInstanceOfSatisfying(WorkSelectionException.class, e -> {
				assertThat(e.code()).isEqualTo("DEMO_ACCOUNT_UNAVAILABLE");
				assertThat(e.status().value()).isEqualTo(409);
			});
			verifyNoInteractions(locks, postings, applications);
		}

		@Test
		@DisplayName("근로자 잠금 행 보장 → 잠금 → 기존 시연 조회 순서, 잠금 실패 → 503·조회 없음")
		void lockOrder() {
			service().prepare(EMPLOYER);
			var order = inOrder(locks, lookup, postings);
			order.verify(locks).ensureExists(WORKER);
			order.verify(locks).lockWorker(WORKER);
			order.verify(lookup).findFixturePosting(EMPLOYER, MARKER);
			order.verify(postings).saveAndFlush(any());
			given(locks.lockWorker(WORKER)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkSelectionException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
			verify(lookup, times(1)).findFixturePosting(anyLong(), anyString());
		}

		@Test
		@DisplayName("같은 고용주·근로자·GMT+9 날짜 → 재사용 created=false·기존 공고 ID, 어떤 저장·상태 변경도 없음")
		void reuse() {
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(4101L));
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(rows(4101L, 11, 501));
			var response = service().prepare(EMPLOYER);
			assertThat(response.created()).isFalse();
			assertThat(response.preparedJobPostingId()).isEqualTo(4101L);
			assertThat(response.cases()).hasSize(11);
			verifyNoInteractions(postings, schedules, revisions, applications, assignments, statuses, changes, completion, passwords);
			verify(members, never()).saveAndFlush(any());
			verify(members, never()).save(any());
		}
	}

	@Nested
	@DisplayName("신규 준비 구성")
	class Create {
		private DemoWorkSelectionFixtureService.FixtureResponse response;
		private JobPosting posting;
		private List<WorkSchedule> saved;

		@BeforeEach
		void prepare() {
			response = service().prepare(EMPLOYER);
			var postingCaptor = ArgumentCaptor.forClass(JobPosting.class);
			verify(postings).saveAndFlush(postingCaptor.capture());
			posting = postingCaptor.getValue();
			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<WorkSchedule>> captor = ArgumentCaptor.forClass(List.class);
			verify(schedules).saveAllAndFlush(captor.capture());
			saved = captor.getValue();
		}
		private WorkSchedule schedule(String type) { return saved.get(TYPES.indexOf(type)); }
		private JobApplication target(String type) {
			long id = schedule(type).getId() == null ? -1 : schedule(type).getId();
			return savedApplications.stream().filter(a -> a.getWorkScheduleId() == 301L + TYPES.indexOf(type) && a.getWorkerId() == WORKER)
				.findFirst().orElseThrow(() -> new AssertionError("no application " + type + " " + id));
		}

		@Test
		@DisplayName("201 대상(created=true)·가상 공고 1개(표식 제목·정원 1·GMT+9·좌표)·일정 11개, 사례 조회는 행위자·GMT+9 오늘")
		void posting() {
			assertThat(response.created()).isTrue();
			assertThat(response.preparedJobPostingId()).isEqualTo(4201L);
			assertThat(posting.getTitle()).isEqualTo(MARKER);
			assertThat(posting.getEmployer()).isSameAs(employer);
			assertThat(posting.getHeadcount()).isEqualTo(1);
			assertThat(posting.getTimeZone()).isEqualTo("GMT+9");
			assertThat(posting.getLatitude()).isNotNull();
			assertThat(posting.getLongitude()).isNotNull();
			assertThat(saved).hasSize(11);
			verify(fixtures).findCases(EMPLOYER, TODAY);
		}

		@Test
		@DisplayName("일정 날짜: 미래 사례 오늘+7+i, 시작 경과 -1일·조기 종료 -2일·완료 -3일")
		void dates() {
			for (int i = 0; i < TYPES.size(); i++) {
				var expected = switch (TYPES.get(i)) {
					case "STARTED" -> TODAY.minusDays(1); case "EARLY_ENDED" -> TODAY.minusDays(2); case "COMPLETED" -> TODAY.minusDays(3);
					default -> TODAY.plusDays(7 + i);
				};
				assertThat(saved.get(i).getWorkDate()).as(TYPES.get(i)).isEqualTo(expected);
			}
		}

		@Test
		@DisplayName("대상 근로자 지원 11건(1회차·5일 전 접수) + 정원 충족용 지원 1건 = 저장 12건")
		void applications() {
			assertThat(savedApplications).hasSize(12);
			var targets = savedApplications.stream().filter(a -> a.getWorkerId() == WORKER).toList();
			assertThat(targets).hasSize(11);
			assertThat(targets).extracting(JobApplication::getWorkScheduleId).containsExactlyElementsOf(
				java.util.stream.LongStream.rangeClosed(301, 311).boxed().toList());
			assertThat(targets).allSatisfy(a -> {
				assertThat(a.getApplicationRound()).isEqualTo(1);
				assertThat(a.getAppliedAtUtc()).isEqualTo(NOW_UTC.minusDays(5));
				assertThat(a.isCurrent()).isTrue();
			});
		}

		@Test
		@DisplayName("조건 변경: CHANGED는 v1 지원 후 v2, STALE_VERSION은 v2 변경 후 지원 — 각 BASELINE→UPDATED 기록")
		void revisions() {
			assertThat(versionAtApplication.get(target("CHANGED").getId())).isEqualTo(1);
			assertThat(schedule("CHANGED").getTermsVersion()).isEqualTo(2);
			assertThat(versionAtApplication.get(target("STALE_VERSION").getId())).isEqualTo(2);
			assertThat(schedule("STALE_VERSION").getTermsVersion()).isEqualTo(2);
			assertThat(schedule("CHANGED").getPlannedStartAt()).isEqualTo(schedule("CHANGED").getWorkDate().atTime(10, 0));
			assertThat(savedRevisions).hasSize(4);
			assertThat(savedRevisions).extracting(WorkScheduleRevision::getRevisionType).containsExactly(
				WorkScheduleRevision.Type.BASELINE, WorkScheduleRevision.Type.UPDATED, WorkScheduleRevision.Type.BASELINE, WorkScheduleRevision.Type.UPDATED);
			assertThat(savedRevisions.get(0).getTermsVersion()).isEqualTo(1);
			assertThat(savedRevisions.get(1).getTermsVersion()).isEqualTo(2);
			assertThat(savedRevisions.get(1).getActorId()).isEqualTo(EMPLOYER);
			for (var type : List.of("NORMAL", "PAUSED", "CLOSED", "REMOVED", "STARTED", "CAPACITY_FULL")) {
				assertThat(schedule(type).getTermsVersion()).as(type).isEqualTo(1);
			}
		}

		@Test
		@DisplayName("취소·조기 종료·완료: 지원 SELECTED + 확정 근무, 고용주 ID로 변경 기록·완료 처리 (내부 검증은 mock)")
		void finishedWork() {
			for (var type : List.of("CANCELLED", "EARLY_ENDED", "COMPLETED")) assertThat(target(type).getStatus()).as(type).isEqualTo("SELECTED");
			assertThat(savedAssignments).hasSize(4);
			assertThat(savedAssignments).allSatisfy(a -> assertThat(a.getConfirmedAtUtc()).isEqualTo(NOW_UTC.minusDays(4)));
			long cancelled = savedAssignments.stream().filter(a -> a.getApplicationId() == target("CANCELLED").getId()).findFirst().orElseThrow().getId();
			long early = savedAssignments.stream().filter(a -> a.getApplicationId() == target("EARLY_ENDED").getId()).findFirst().orElseThrow().getId();
			long done = savedAssignments.stream().filter(a -> a.getApplicationId() == target("COMPLETED").getId()).findFirst().orElseThrow().getId();
			verify(changes).recordValidatedChange(eq(EMPLOYER), eq(cancelled), eq(WorkChange.Type.CANCELLED), eq(null),
				eq(WorkChange.Reason.WEATHER), eq(WorkChange.WeatherReason.RAIN), anyString());
			verify(changes).recordValidatedChange(eq(EMPLOYER), eq(early), eq(WorkChange.Type.EARLY_ENDED),
				eq(OffsetDateTime.parse(TODAY.minusDays(2) + "T14:00:00+09:00")), eq(WorkChange.Reason.WEATHER), eq(WorkChange.WeatherReason.RAIN), anyString());
			verify(completion).complete(EMPLOYER, done);
			verify(changes, times(2)).recordValidatedChange(anyLong(), anyLong(), any(), any(), any(), any(), anyString());
			for (var type : List.of("NORMAL", "CHANGED", "STALE_VERSION", "PAUSED", "CLOSED", "REMOVED", "STARTED"))
				assertThat(target(type).getStatus()).as(type).isEqualTo("PENDING");
		}

		@Test
		@DisplayName("일시중단·마감·모집 취소: 고용주·공고·일정·표식·상태·서버 시각으로 상태 변경")
		void statuses() {
			for (var type : List.of("PAUSED", "CLOSED", "REMOVED")) {
				verify(statuses).setNewScheduleStatus(EMPLOYER, 4201L, 301L + TYPES.indexOf(type), MARKER, type, NOW_UTC);
			}
			verify(statuses, times(3)).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
		}

		@Test
		@DisplayName("정원 충족: 새 가상 근로자(무작위 비밀번호 인코딩)·지원·확정 근무, 기존 회원 이름·전화·비밀번호 해시 불변")
		void capacity() {
			var captor = ArgumentCaptor.forClass(Member.class);
			verify(members, times(1)).saveAndFlush(captor.capture());
			var other = captor.getValue();
			assertThat(other.getLoginId()).startsWith("demo-selection-");
			assertThat(other.memberRole()).contains(MemberRole.WORKER);
			assertThat(other.displayName()).isEqualTo("정원 확인용 가상 근로자");
			assertThat(other.passwordHash()).isEqualTo("{bcrypt}ENCODED-RANDOM");
			var raw = ArgumentCaptor.forClass(String.class);
			verify(passwords).encode(raw.capture());
			assertThat(raw.getValue()).matches("[0-9a-f-]{36}");
			var occupied = savedApplications.stream().filter(a -> a.getWorkerId() == 900L).toList();
			assertThat(occupied).hasSize(1);
			assertThat(occupied.getFirst().getWorkScheduleId()).isEqualTo(301L + TYPES.indexOf("CAPACITY_FULL"));
			assertThat(occupied.getFirst().getStatus()).isEqualTo("SELECTED");
			assertThat(savedAssignments).anyMatch(a -> a.getApplicationId() == occupied.getFirst().getId());
			assertThat(target("CAPACITY_FULL").getStatus()).isEqualTo("PENDING");
			for (var m : List.of(employer, worker)) {
				assertThat(m.displayName()).startsWith("기존 회원 ");
				assertThat(m.passwordHash()).startsWith("existing-hash-");
				assertThat(ReflectionTestUtils.getField(m, "phone")).isEqualTo("01099990000");
			}
		}

		@Test
		@DisplayName("응답 JSON에 비밀번호·해시·인코딩 값·전화번호 없음")
		void noSecrets() {
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(rows(4201L, 11, 1001));
			var json = JsonMapper.builder().build().writeValueAsString(service().prepare(EMPLOYER));
			assertThat(json).doesNotContain("password", "hash", "ENCODED", "01099990000", "bcrypt");
		}
	}

	@Nested
	@DisplayName("실패 시 성공 응답 없음")
	class Failure {
		@Test
		@DisplayName("상태 준비 결과 ≠ 1 → 503, 사례 조회 응답 없음")
		void statusFails() {
			given(statuses.setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), eq("CLOSED"), any())).willReturn(0);
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOfSatisfying(WorkSelectionException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
			verify(fixtures, never()).findCases(anyLong(), any());
			verify(members, never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("저장 실패(지원 저장 예외)·완료 처리 예외는 전파, 이후 작업·응답 없음 (DB 롤백은 미검증)")
		void saveFails() {
			willThrow(new DataIntegrityViolationException("dup")).given(applications).saveAndFlush(any());
			assertThatThrownBy(() -> service().prepare(EMPLOYER)).isInstanceOf(DataIntegrityViolationException.class);
			verifyNoInteractions(changes, completion, statuses);
			verify(fixtures, never()).findCases(anyLong(), any());
		}
	}

	@Nested
	@DisplayName("사례 조회·매핑")
	class Read {
		@Test
		@DisplayName("조회는 행위자 ID·GMT+9 오늘로만, 저장 없음; 공고별로 일정 순서대로 11개 사례 종류 연결")
		void list() {
			var both = new ArrayList<>(rows(4201L, 11, 1001));
			both.addAll(rows(4101L, 11, 2001));
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(both);
			var response = service().list(EMPLOYER);
			assertThat(response.created()).isFalse();
			assertThat(response.preparedJobPostingId()).isNull();
			assertThat(response.cases()).extracting(DemoWorkSelectionFixtureService.Case::scenario)
				.containsExactlyElementsOf(java.util.stream.Stream.concat(TYPES.stream(), TYPES.stream()).toList());
			assertThat(response.cases().get(1).label()).isEqualTo("변경 조건 재확인 후 선정");
			verify(fixtures).findCases(EMPLOYER, TODAY);
			verifyNoInteractions(postings, applications, assignments, locks);
		}

		@Test
		@DisplayName("계정 전환: 고용주·근로자가 같은 공고·지원 ID를 조회하면 같은 사례 종류")
		void switchAccount() {
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(rows(4201L, 11, 1001));
			given(fixtures.findCases(WORKER, TODAY)).willReturn(rows(4201L, 11, 1001));
			var asEmployer = service().list(EMPLOYER).cases();
			var asWorker = service().list(WORKER).cases();
			for (int i = 0; i < 11; i++) {
				assertThat(asWorker.get(i).applicationId()).isEqualTo(asEmployer.get(i).applicationId());
				assertThat(asWorker.get(i).scenario()).isEqualTo(asEmployer.get(i).scenario());
			}
		}

		@Test
		@DisplayName("정상 선정으로 상태가 바뀌어도(SELECTED·CONFIRMED) 사례 종류는 일정 순서로 유지")
		void statusChangeKeepsScenario() {
			var list = new ArrayList<>(rows(4201L, 11, 1001));
			list.set(0, caseRow(4201L, 300, 1001, "SELECTED", "CONFIRMED"));
			list.set(1, caseRow(4201L, 301, 1002, "SELECTED", "CONFIRMED"));
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(list);
			var cases = service().list(EMPLOYER).cases();
			assertThat(cases.get(0).scenario()).isEqualTo("NORMAL");
			assertThat(cases.get(0).workStatus()).isEqualTo("CONFIRMED");
			assertThat(cases.get(1).scenario()).isEqualTo("CHANGED");
		}

		@Test
		@DisplayName("한 공고에 12행 이상 → 503 (사례 종류를 추측해 붙이지 않음)")
		void tooMany() {
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(rows(4201L, 12, 1001));
			assertThatThrownBy(() -> service().list(EMPLOYER)).isInstanceOfSatisfying(WorkSelectionException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
		}

		@Test
		@DisplayName("선언: 사례 SQL은 행위자가 고용주이거나 지원자인 현재 회차만, 표식 제목을 실제 고용주·근로자 ID·오늘로 비교, 공고 desc·일정 asc")
		void sql() throws Exception {
			var sql = DemoWorkSelectionRepository.class.getMethod("findCases", long.class, LocalDate.class).getAnnotation(Query.class).value()
				.replaceAll("\\s+", " ");
			assertThat(sql).contains("(posting.employer_id = :actorId or app.worker_id = :actorId) and app.is_current = 1",
				"posting.title = concat('[WORK-02 시연·고용주 ', posting.employer_id, '·근로자 ', app.worker_id, '·', cast(:today as char), ']')",
				"order by posting.id desc, schedule.id");
			assertThat(sql).doesNotContain("password", "phone");
		}

		@Test
		@DisplayName("신규 준비 중 자정이 지나도 생성 기준 날짜의 공고·지원 11건을 응답한다")
		void midnightCreationKeepsBasisDate() {
			var beforeMidnight = Instant.parse("2026-11-28T14:59:59.999999Z");
			var afterMidnight = Instant.parse("2026-11-28T15:00:00Z");
			var current = new AtomicReference<>(beforeMidnight);
			var clock = mock(Clock.class);
			given(clock.instant()).willAnswer(call -> current.get());
			// 마지막 정원 충족 사례의 비밀번호 인코딩 중 실제로 날짜가 넘어간 상황을 만든다.
			given(passwords.encode(anyString())).willAnswer(call -> {
				current.set(afterMidnight);
				return "{bcrypt}ENCODED-RANDOM";
			});
			// 이 준비에서 저장한 본인 지원만 반환한다. 다음 날 조회의 기본 반환값은 빈 목록이다.
			given(fixtures.findCases(EMPLOYER, TODAY)).willAnswer(call -> savedApplications.stream()
				.filter(app -> app.getWorkerId() == WORKER)
				.map(app -> caseRow(4201L, app.getWorkScheduleId(), app.getId(), app.getStatus(),
					"SELECTED".equals(app.getStatus()) ? "CONFIRMED" : "PENDING"))
				.toList());

			var response = service(clock).prepare(EMPLOYER);

			assertThat(current.get()).isEqualTo(afterMidnight);
			assertThat(response.created()).isTrue();
			assertThat(response.preparedJobPostingId()).isEqualTo(4201L);
			assertThat(response.cases()).hasSize(11).allMatch(item -> item.jobPostingId() == 4201L);
			assertThat(response.cases()).extracting(DemoWorkSelectionFixtureService.Case::applicationId)
				.containsExactlyElementsOf(savedApplications.stream().filter(app -> app.getWorkerId() == WORKER)
					.map(JobApplication::getId).toList());
			assertThat(response.cases()).extracting(DemoWorkSelectionFixtureService.Case::scenario)
				.containsExactlyElementsOf(TYPES);
			var postingCaptor = ArgumentCaptor.forClass(JobPosting.class);
			verify(postings).saveAndFlush(postingCaptor.capture());
			assertThat(postingCaptor.getValue().getTitle()).isEqualTo(MARKER);
			verify(lookup).findFixturePosting(EMPLOYER, MARKER);
			verify(fixtures).findCases(EMPLOYER, TODAY);
			verify(fixtures, never()).findCases(EMPLOYER, TODAY.plusDays(1));
			verify(clock, times(1)).instant();
		}

		@Test
		@DisplayName("재사용 조회 중 자정이 지나도 같은 기준 날짜의 기존 사례 11건을 반환하고 저장하지 않는다")
		void midnightReuseKeepsBasisDate() {
			var beforeMidnight = Instant.parse("2026-11-28T14:59:59.999999Z");
			var afterMidnight = Instant.parse("2026-11-28T15:00:00Z");
			var current = new AtomicReference<>(beforeMidnight);
			var clock = mock(Clock.class);
			given(clock.instant()).willAnswer(call -> current.get());
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willAnswer(call -> {
				current.set(afterMidnight);
				return Optional.of(4101L);
			});
			given(fixtures.findCases(EMPLOYER, TODAY)).willReturn(rows(4101L, 11, 501));

			var response = service(clock).prepare(EMPLOYER);

			assertThat(current.get()).isEqualTo(afterMidnight);
			assertThat(response.created()).isFalse();
			assertThat(response.preparedJobPostingId()).isEqualTo(4101L);
			assertThat(response.cases()).hasSize(11).allMatch(item -> item.jobPostingId() == 4101L);
			assertThat(response.cases()).extracting(DemoWorkSelectionFixtureService.Case::applicationId)
				.containsExactlyElementsOf(java.util.stream.LongStream.range(501L, 512L).boxed().toList());
			assertThat(response.cases()).extracting(DemoWorkSelectionFixtureService.Case::scenario)
				.containsExactlyElementsOf(TYPES);
			verify(lookup).findFixturePosting(EMPLOYER, MARKER);
			verify(fixtures).findCases(EMPLOYER, TODAY);
			verify(fixtures, never()).findCases(EMPLOYER, TODAY.plusDays(1));
			verify(clock, times(1)).instant();
			verifyNoInteractions(postings, schedules, revisions, applications, assignments, statuses,
				changes, completion, passwords);
			verify(members, never()).saveAndFlush(any());
		}
	}
}

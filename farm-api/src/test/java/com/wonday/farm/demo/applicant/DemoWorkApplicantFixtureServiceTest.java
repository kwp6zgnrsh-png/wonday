package com.wonday.farm.demo.applicant;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.application.testsupport.ApplicationTestData.memberWithRoleCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.demo.application.DemoMyApplicationFixtureRepository;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 서비스 + 고정 Clock, 저장소·PasswordEncoder는 mock. 실제 INSERT/UPDATE·잠금·롤백은 실행하지 않는다.
@DisplayName("farm-demo WORK-01 지원자 시연 데이터 준비 서비스")
class DemoWorkApplicantFixtureServiceTest {
	private static final long EMPLOYER = 10L;
	private static final long POSTING = 4101L;
	// now = 2026-11-30T15:30:00.123456Z → GMT+9 오늘 2026-12-01
	private static final Instant NOW = Instant.parse("2026-11-30T15:30:00.1234567Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-30T15:30:00.123456");
	private static final LocalDate TODAY = LocalDate.parse("2026-12-01");
	private static final String MARKER = "[WORK-01 시연·고용주 10·2026-12-01]";
	private static final String HASH = "$2a$10$fakehashfakehashfakehashfakehashfakehashfakehashfakeh";

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkScheduleRevisionRepository revisions = mock(WorkScheduleRevisionRepository.class);
	private final DemoWorkApplicantFixtureRepository fixtures = mock(DemoWorkApplicantFixtureRepository.class);
	private final DemoJobApplicationRepository lookup = mock(DemoJobApplicationRepository.class);
	private final DemoMyApplicationFixtureRepository assignments = mock(DemoMyApplicationFixtureRepository.class);
	private final PasswordEncoder passwords = mock(PasswordEncoder.class);
	private final Member employer = member(EMPLOYER, MemberRole.EMPLOYER);

	private final List<Member> newWorkers = new ArrayList<>();
	private final List<WorkSchedule> savedSchedules = new ArrayList<>();
	private final List<Object[]> inserted = new ArrayList<>(); // workerId, scheduleId, status, round, version, current, at
	private final List<Map<String, Object>> revisionSnapshots = new ArrayList<>();

	private DemoWorkApplicantFixtureService service(Instant now) {
		return new DemoWorkApplicantFixtureService(members, postings, schedules, revisions, fixtures, lookup, assignments, passwords,
			Clock.fixed(now, ZoneOffset.UTC));
	}
	private final DemoWorkApplicantFixtureService service = service(NOW);
	private static long appId(long worker, long schedule, int round) { return worker * 1000 + schedule * 10 + round; }

	@BeforeEach
	void stubs() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(employer));
		given(fixtures.lockEmployer(EMPLOYER)).willReturn(Optional.of(employer));
		given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.empty());
		given(passwords.encode(anyString())).willReturn(HASH);
		given(members.saveAllAndFlush(anyList())).willAnswer(call -> {
			List<Member> items = call.getArgument(0);
			for (int i = 0; i < items.size(); i++) {
				assertThat(items.get(i).getId()).as("새 회원만 저장").isNull();
				ReflectionTestUtils.setField(items.get(i), "id", 9001L + i);
			}
			newWorkers.addAll(items);
			return items;
		});
		given(postings.saveAndFlush(any(JobPosting.class))).willAnswer(call -> {
			JobPosting posting = call.getArgument(0);
			ReflectionTestUtils.setField(posting, "id", POSTING);
			return posting;
		});
		given(schedules.saveAllAndFlush(anyList())).willAnswer(call -> {
			List<WorkSchedule> items = new ArrayList<>(call.getArgument(0));
			for (int i = 0; i < items.size(); i++) ReflectionTestUtils.setField(items.get(i), "id", 301L + i);
			savedSchedules.addAll(items);
			return items;
		});
		given(schedules.saveAndFlush(any(WorkSchedule.class))).willAnswer(call -> call.getArgument(0));
		given(revisions.save(any(WorkScheduleRevision.class))).willAnswer(call -> {
			var snapshot = new LinkedHashMap<String, Object>();
			for (String f : new String[] {"termsVersion", "revisionType", "actorId", "recordedAtUtc", "plannedStartAt", "plannedEndAt"}) {
				snapshot.put(f, ReflectionTestUtils.getField((Object) call.getArgument(0), f));
			}
			snapshot.put("insertedBefore", inserted.size());
			revisionSnapshots.add(snapshot);
			return call.getArgument(0);
		});
		given(fixtures.insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()))
			.willAnswer(call -> {
				inserted.add(new Object[] {call.getArgument(0), call.getArgument(1), call.getArgument(5), call.getArgument(6),
					call.getArgument(7), call.getArgument(8), call.getArgument(9)});
				return 1;
			});
		given(assignments.findApplicationId(anyLong(), anyLong(), anyInt()))
			.willAnswer(call -> Optional.of(appId(call.getArgument(0), call.getArgument(1), call.getArgument(2))));
		given(assignments.insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).willReturn(1);
		given(fixtures.setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any())).willReturn(1);
		given(fixtures.findDates(anyLong(), anyLong())).willReturn(List.of());
		given(fixtures.findCases(anyLong(), anyLong())).willReturn(List.of());
	}

	private void expectUnavailable() {
		assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOfSatisfying(ApplicationException.class,
			e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
		verify(fixtures, never()).findCases(anyLong(), anyLong());
	}
	private static DemoWorkApplicantFixtureRepository.CaseRow caseRow(long id, String status, int round, int current, Integer applied,
			int latest, int phone) {
		var v = new LinkedHashMap<String, Object>();
		v.put("applicationId", id); v.put("workScheduleId", 301L); v.put("name", "시연 근로자"); v.put("status", status);
		v.put("applicationRound", round); v.put("currentFlag", current); v.put("appliedTermsVersion", applied);
		v.put("latestTermsVersion", latest); v.put("phonePresent", phone);
		return MyApplicationRows.stub(DemoWorkApplicantFixtureRepository.CaseRow.class, v);
	}

	@Nested
	@DisplayName("역할·잠금·재사용")
	class Guards {
		@Test
		@DisplayName("고용주가 아니면(근로자·모르는 역할·없음) AccessDenied, 잠금·저장 없음")
		void notEmployer() {
			for (var found : List.of(Optional.of(member(EMPLOYER, MemberRole.WORKER)), Optional.of(memberWithRoleCode(EMPLOYER, "ADMIN")),
					Optional.<Member>empty())) {
				given(members.findById(EMPLOYER)).willReturn(found);
				assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOf(AccessDeniedException.class);
			}
			verifyNoInteractions(fixtures, lookup, postings, schedules, revisions, assignments, passwords);
		}

		@Test
		@DisplayName("고용주 행 잠금 실패(역할 조건 불일치 포함)는 AccessDenied, 저장 없음")
		void lockFails() {
			given(fixtures.lockEmployer(EMPLOYER)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.prepare(EMPLOYER)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(lookup, postings, schedules, revisions, assignments, passwords);
			verify(members, never()).saveAllAndFlush(anyList());
		}

		@Test
		@DisplayName("순서: 역할 확인 → 고용주 행 잠금 → 오늘 표식 조회")
		void order() {
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(POSTING));
			service.prepare(EMPLOYER);
			var order = inOrder(members, fixtures, lookup);
			order.verify(members).findById(EMPLOYER);
			order.verify(fixtures).lockEmployer(EMPLOYER);
			order.verify(lookup).findFixturePosting(EMPLOYER, MARKER);
		}

		@Test
		@DisplayName("같은 고용주·GMT+9 같은 날 데이터가 있으면 created=false, 회원·공고·일정·지원·약정·상태 변경 없음")
		void reuse() {
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(POSTING));
			var response = service.prepare(EMPLOYER);
			assertThat(response.created()).isFalse();
			assertThat(response.jobPostingId()).isEqualTo(POSTING);
			verify(members, never()).saveAllAndFlush(anyList());
			verifyNoInteractions(postings, schedules, revisions, assignments, passwords);
			verify(fixtures, never()).insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any());
			verify(fixtures, never()).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
			verify(fixtures).findDates(EMPLOYER, POSTING);
			verify(fixtures).findCases(EMPLOYER, POSTING);
		}

		@Test
		@DisplayName("GMT+9 날짜 경계: UTC 14:59:59.999999는 그날, 15:00은 다음 날 표식")
		void boundary() {
			given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.of(POSTING));
			service(Instant.parse("2026-11-30T14:59:59.999999Z")).prepare(EMPLOYER);
			service(Instant.parse("2026-11-30T15:00:00Z")).prepare(EMPLOYER);
			verify(lookup).findFixturePosting(EMPLOYER, "[WORK-01 시연·고용주 10·2026-11-30]");
			verify(lookup).findFixturePosting(EMPLOYER, "[WORK-01 시연·고용주 10·2026-12-01]");
		}
	}

	@Nested
	@DisplayName("신규 구성")
	class Create {
		@Test
		@DisplayName("새 가상 근로자 5명: 근로자 역할·고유 로그인 ID·번호 4명(10~11자리)·미등록 1명, 무작위 비밀번호의 해시만 저장")
		void workers() {
			assertThat(service.prepare(EMPLOYER).created()).isTrue();
			assertThat(newWorkers).hasSize(5);
			assertThat(newWorkers).extracting(m -> ReflectionTestUtils.getField(m, "role")).containsOnly("WORKER");
			assertThat(newWorkers).extracting(m -> (String) ReflectionTestUtils.getField(m, "loginId")).allMatch(id -> id.startsWith("demo-applicant-"))
				.doesNotHaveDuplicates();
			assertThat(newWorkers).extracting(m -> ReflectionTestUtils.getField(m, "phone"))
				.containsExactly("01000001001", "01000001002", "01000001003", "01000001004", null);
			assertThat(newWorkers).extracting(m -> ReflectionTestUtils.getField(m, "passwordHash")).containsOnly(HASH);
			var raw = ArgumentCaptor.forClass(CharSequence.class);
			verify(passwords).encode(raw.capture());
			assertThat(raw.getValue().toString()).matches("[0-9a-f-]{36}");
			verify(members, times(1)).saveAllAndFlush(anyList());
		}

		@Test
		@DisplayName("공고: 잠근 본인 고용주 소유·표식 제목·정원 3·GMT+9, 일정 4개(오늘+7, +8, -1, +9)")
		void postingAndSchedules() {
			service.prepare(EMPLOYER);
			var captor = ArgumentCaptor.forClass(JobPosting.class);
			verify(postings).saveAndFlush(captor.capture());
			assertThat(ReflectionTestUtils.getField(captor.getValue(), "employer")).isSameAs(employer);
			assertThat(ReflectionTestUtils.getField(captor.getValue(), "title")).isEqualTo(MARKER);
			assertThat(ReflectionTestUtils.getField(captor.getValue(), "headcount")).isEqualTo(3);
			assertThat(ReflectionTestUtils.getField(captor.getValue(), "timeZone")).isEqualTo("GMT+9");
			assertThat(savedSchedules).extracting(s -> ReflectionTestUtils.getField(s, "workDate"))
				.containsExactly(TODAY.plusDays(7), TODAY.plusDays(8), TODAY.minusDays(1), TODAY.plusDays(9));
		}

		@Test
		@DisplayName("지원 9건(현재 8·과거 1): 근로자·일정·상태·회차·버전·현재 여부·지원 시각")
		void applications() {
			service.prepare(EMPLOYER);
			Object[][] expected = {
				{9001L, 301L, "PENDING", 1, 1, 1, NOW_UTC.minusMinutes(30)},
				{9002L, 301L, "PENDING", 1, 2, 1, NOW_UTC.minusMinutes(20)},
				{9003L, 301L, "SELECTED", 1, 2, 1, NOW_UTC.minusMinutes(15)},
				{9004L, 301L, "NOT_SELECTED", 1, 2, 1, NOW_UTC.minusMinutes(10)},
				{9005L, 301L, "NOT_SELECTED", 1, 1, 0, NOW_UTC.minusMinutes(40)},
				{9005L, 301L, "PENDING", 2, 2, 1, NOW_UTC.minusMinutes(10)},
				{9001L, 302L, "PENDING", 1, 1, 1, NOW_UTC.minusMinutes(9)},
				{9002L, 303L, "NOT_SELECTED", 1, 1, 1, NOW_UTC.minusDays(2)},
				{9004L, 304L, "PENDING", 1, 1, 1, NOW_UTC.minusMinutes(8)}};
			assertThat(inserted).hasSize(9);
			for (int i = 0; i < expected.length; i++) assertThat(inserted.get(i)).as("지원 " + i).containsExactly(expected[i]);
			assertThat(inserted.stream().filter(r -> (int) r[5] == 1).count()).isEqualTo(8);
			verify(fixtures, times(9)).insertApplication(anyLong(), anyLong(), eq(POSTING), eq(EMPLOYER), eq(MARKER), anyString(), anyInt(), anyInt(), anyInt(), any());
		}

		@Test
		@DisplayName("첫 일정: 현재 대기 3·선정 1·미선정 1·과거 1, 같은 접수 시각 사례, 재지원자는 1회차(과거)·2회차(현재)")
		void firstSchedule() {
			service.prepare(EMPLOYER);
			var first = inserted.stream().filter(r -> (long) r[1] == 301L).toList();
			var current = first.stream().filter(r -> (int) r[5] == 1).toList();
			assertThat(current.stream().filter(r -> r[2].equals("PENDING")).count()).isEqualTo(3);
			assertThat(current.stream().filter(r -> r[2].equals("SELECTED")).count()).isEqualTo(1);
			assertThat(current.stream().filter(r -> r[2].equals("NOT_SELECTED")).count()).isEqualTo(1);
			assertThat(first.stream().filter(r -> (int) r[5] == 0).count()).isEqualTo(1);
			assertThat(current.stream().map(r -> r[6]).distinct().count()).as("같은 접수 시각 존재").isLessThan(current.size());
			var reapply = first.stream().filter(r -> (long) r[0] == 9005L).toList();
			assertThat(reapply).extracting(r -> r[3]).containsExactly(1, 2);
			assertThat(reapply).extracting(r -> r[5]).containsExactly(0, 1);
		}

		@Test
		@DisplayName("조건 변경: 첫 지원(v1) 뒤 BASELINE(v1, 09~17) → 일정 v2(10~18) → UPDATED(v2, 고용주), 나머지 첫 일정 현재 지원은 v2")
		void conditionChange() {
			service.prepare(EMPLOYER);
			assertThat(revisionSnapshots).hasSize(2);
			var day = TODAY.plusDays(7);
			assertThat(revisionSnapshots.get(0)).containsEntry("termsVersion", 1).containsEntry("actorId", null)
				.containsEntry("plannedStartAt", day.atTime(9, 0)).containsEntry("insertedBefore", 1);
			assertThat(revisionSnapshots.get(0).get("revisionType").toString()).isEqualTo("BASELINE");
			assertThat(revisionSnapshots.get(1)).containsEntry("termsVersion", 2).containsEntry("actorId", EMPLOYER)
				.containsEntry("plannedStartAt", day.atTime(10, 0)).containsEntry("plannedEndAt", day.atTime(18, 0));
			assertThat(revisionSnapshots.get(1).get("revisionType").toString()).isEqualTo("UPDATED");
			assertThat(ReflectionTestUtils.getField(savedSchedules.get(0), "termsVersion")).isEqualTo(2);
			verify(schedules).saveAndFlush(savedSchedules.get(0));
			assertThat(inserted.subList(1, 6)).filteredOn(r -> (int) r[5] == 1).allSatisfy(r -> assertThat(r[4]).isEqualTo(2));
		}

		@Test
		@DisplayName("확정 약정 1건: 선정 지원의 실제 ID·그 근로자·공고·고용주·표식으로만 INSERT")
		void assignment() {
			service.prepare(EMPLOYER);
			verify(assignments).insertAssignment(appId(9003, 301, 1), 9003L, POSTING, EMPLOYER, MARKER, NOW_UTC.minusMinutes(14));
			verify(assignments, times(1)).insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("다른 일정 상태: 둘째 PAUSED·셋째(지난 날짜) CLOSED·넷째 REMOVED를 새 일정 ID·고용주·공고·표식으로만 변경")
		void scheduleStatuses() {
			service.prepare(EMPLOYER);
			verify(fixtures).setNewScheduleStatus(EMPLOYER, POSTING, 302L, MARKER, "PAUSED", NOW_UTC);
			verify(fixtures).setNewScheduleStatus(EMPLOYER, POSTING, 303L, MARKER, "CLOSED", NOW_UTC);
			verify(fixtures).setNewScheduleStatus(EMPLOYER, POSTING, 304L, MARKER, "REMOVED", NOW_UTC);
			verify(fixtures, times(3)).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
			verify(fixtures, never()).setNewScheduleStatus(anyLong(), anyLong(), eq(301L), anyString(), anyString(), any());
			assertThat(ReflectionTestUtils.getField(savedSchedules.get(2), "workDate")).isEqualTo(TODAY.minusDays(1));
		}
	}

	@Nested
	@DisplayName("실패 시 후속 작업 중단")
	class Failures {
		@Test
		@DisplayName("지원 INSERT 0건 → 503, 이후 이력·약정·상태 변경 없음")
		void insertFails() {
			given(fixtures.insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), anyInt(), any()))
				.willReturn(0);
			expectUnavailable();
			verifyNoInteractions(revisions, assignments);
			verify(fixtures, never()).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
		}

		@Test
		@DisplayName("지원 ID 재조회 실패 → 503")
		void idMissing() {
			given(assignments.findApplicationId(anyLong(), anyLong(), anyInt())).willReturn(Optional.empty());
			expectUnavailable();
		}

		@Test
		@DisplayName("약정 INSERT 0건 → 503, 미선정·재지원·다른 일정 지원과 상태 변경 없음")
		void assignmentFails() {
			given(assignments.insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).willReturn(0);
			expectUnavailable();
			assertThat(inserted).hasSize(3);
			verify(fixtures, never()).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
		}

		@Test
		@DisplayName("일정 상태 변경 0건 → 503, 나머지 상태 변경·응답 조회 없음")
		void statusFails() {
			given(fixtures.setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any())).willReturn(0);
			expectUnavailable();
			verify(fixtures, times(1)).setNewScheduleStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
			verify(fixtures, never()).findDates(anyLong(), anyLong());
		}

		@Test
		@DisplayName("회원 저장 예외는 전파, 공고·지원 저장 없음 (실제 롤백은 확인하지 않음)")
		void memberSaveFails() {
			var failure = new IllegalStateException("save failed");
			given(members.saveAllAndFlush(anyList())).willThrow(failure);
			assertThatThrownBy(() -> service.prepare(EMPLOYER)).isSameAs(failure);
			verifyNoInteractions(postings, schedules, assignments);
		}
	}

	@Nested
	@DisplayName("응답")
	class Response {
		@Test
		@DisplayName("사례 이름: 과거·연락처 미등록·선정·미선정·조건 변경·대기, 일정 상태·버전 전달")
		void labels() {
			given(fixtures.findCases(EMPLOYER, POSTING)).willReturn(List.of(
				caseRow(1, "NOT_SELECTED", 1, 0, 1, 2, 1), caseRow(2, "PENDING", 2, 1, 2, 2, 0), caseRow(3, "SELECTED", 1, 1, 2, 2, 1),
				caseRow(4, "NOT_SELECTED", 1, 1, 2, 2, 1), caseRow(5, "PENDING", 1, 1, 1, 2, 1), caseRow(6, "PENDING", 1, 1, 2, 2, 1),
				caseRow(7, "PENDING", 1, 1, null, 2, 1)));
			var date = MyApplicationRows.stub(DemoWorkApplicantFixtureRepository.DateRow.class, Map.of("workScheduleId", 302L,
				"workDate", TODAY.plusDays(8), "status", "PAUSED", "termsVersion", 1));
			given(fixtures.findDates(EMPLOYER, POSTING)).willReturn(List.of(date));
			var response = service.prepare(EMPLOYER);
			assertThat(response.scenarios()).extracting(DemoWorkApplicantFixtureService.Case::label).containsExactly(
				"과거 회차: 목록 제외·상세 404·연락처 403", "재지원 현재 회차·연락처 미등록", "선정·확정 약정", "미선정·연락처 403",
				"지원 후 조건 변경", "지원 대기", "지원 대기");
			assertThat(response.scenarios().getFirst().current()).isFalse();
			assertThat(response.schedules().getFirst().status()).isEqualTo("PAUSED");
		}

		@Test
		@DisplayName("응답 레코드에 비밀번호·해시·키·전화번호 필드 없음")
		void noSecrets() {
			for (Class<?> type : List.of(DemoWorkApplicantFixtureService.FixtureResponse.class, DemoWorkApplicantFixtureService.Date.class,
					DemoWorkApplicantFixtureService.Case.class)) {
				assertThat(Arrays.stream(type.getRecordComponents()).map(c -> c.getName().toLowerCase()))
					.noneMatch(n -> n.contains("password") || n.contains("hash") || n.contains("key") || n.contains("phone") || n.contains("login"));
			}
		}
	}

	@Test
	@DisplayName("선언: READ_COMMITTED 트랜잭션, 고용주 행 PESSIMISTIC_WRITE 잠금(EMPLOYER 조건), farm-demo 프로필, 쓰기 SQL 범위 (선언만 확인)")
	void declarations() throws Exception {
		var tx = DemoWorkApplicantFixtureService.class.getMethod("prepare", long.class).getAnnotation(Transactional.class);
		assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(tx.readOnly()).isFalse();
		var lock = DemoWorkApplicantFixtureRepository.class.getMethod("lockEmployer", long.class);
		assertThat(lock.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(lock.getAnnotation(Query.class).value()).contains("m.role = 'EMPLOYER'");
		for (Class<?> type : List.of(DemoWorkApplicantFixtureService.class, DemoWorkApplicantFixtureRepository.class, DemoWorkApplicantApi.class,
				DemoWorkApplicantErrorHandler.class)) {
			assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
		}
		for (var method : DemoWorkApplicantFixtureRepository.class.getDeclaredMethods()) {
			if (!method.isAnnotationPresent(Modifying.class)) continue;
			String sql = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ");
			assertThat(sql).as(method.getName()).contains("posting.id = :postingId", "posting.employer_id = :employerId", "posting.title = :marker")
				.doesNotContainIgnoringCase("delete").doesNotContain("member ");
		}
		String status = DemoWorkApplicantFixtureRepository.class.getMethod("setNewScheduleStatus", long.class, long.class, long.class,
			String.class, String.class, LocalDateTime.class).getAnnotation(Query.class).value().replaceAll("\\s+", " ");
		assertThat(status).contains("schedule.id = :scheduleId", "schedule.status = 'OPEN'");
	}
}

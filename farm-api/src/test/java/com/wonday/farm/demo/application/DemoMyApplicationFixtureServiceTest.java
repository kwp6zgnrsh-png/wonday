package com.wonday.farm.demo.application;

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
import static org.mockito.ArgumentMatchers.isNull;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.wonday.farm.application.entity.ApplicationRequestLock;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.service.WorkCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 서비스 + 고정 Clock, 저장소·WorkChangeRecorder·WorkCompletionService는 mock.
// INSERT SQL 실행, 실제 롤백, 잠금에 의한 동시 실행 직렬화, DB 제약·중복 방지는 확인하지 않는다.
@DisplayName("farm-demo APP-04 상태별 시연 데이터 준비 서비스")
class DemoMyApplicationFixtureServiceTest {
	private static final long WORKER = 5L;
	private static final long EMPLOYER = 10L;
	private static final long POSTING = 4001L;
	// now = 2026-11-30T15:30:00.123456Z → GMT+9 오늘 2026-12-01
	private static final Instant NOW = Instant.parse("2026-11-30T15:30:00.1234567Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.parse("2026-11-30T15:30:00.123456");
	private static final LocalDate TODAY = LocalDate.parse("2026-12-01");
	private static final String MARKER = "[APP-04 시연·근로자 5·2026-12-01]";

	private final MemberRepository members = mock(MemberRepository.class);
	private final JobPostingRepository postings = mock(JobPostingRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkScheduleRevisionRepository revisions = mock(WorkScheduleRevisionRepository.class);
	private final DemoJobApplicationRepository lookup = mock(DemoJobApplicationRepository.class);
	private final DemoMyApplicationFixtureRepository fixtures = mock(DemoMyApplicationFixtureRepository.class);
	private final ApplicationRequestLockRepository locks = mock(ApplicationRequestLockRepository.class);
	private final WorkChangeRecorder changes = mock(WorkChangeRecorder.class);
	private final WorkCompletionService completion = mock(WorkCompletionService.class);
	private final Member employer = member(EMPLOYER, MemberRole.EMPLOYER);

	// 저장 순서대로 기록한 INSERT (일정 ID, 상태, 회차, 현재 여부, 지원 시각)
	private final List<Object[]> inserted = new ArrayList<>();
	private final List<WorkSchedule> savedSchedules = new ArrayList<>();
	private final List<Map<String, Object>> revisionSnapshots = new ArrayList<>();

	private DemoMyApplicationFixtureService service(Instant now) {
		return new DemoMyApplicationFixtureService(members, postings, schedules, revisions, lookup, fixtures, locks, changes, completion,
			Clock.fixed(now, ZoneOffset.UTC));
	}
	private final DemoMyApplicationFixtureService service = service(NOW);

	private static long applicationId(long scheduleId, int round) { return scheduleId * 10 + round; }
	private static long assignmentId(long applicationId) { return applicationId + 50000; }

	@BeforeEach
	void stubs() {
		given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.WORKER)));
		given(locks.lockWorker(WORKER)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));
		given(members.findByLoginId("demo-employer")).willReturn(Optional.of(employer));
		given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.empty());
		given(postings.saveAndFlush(any(JobPosting.class))).willAnswer(call -> {
			JobPosting posting = call.getArgument(0);
			ReflectionTestUtils.setField(posting, "id", POSTING);
			return posting;
		});
		given(schedules.saveAllAndFlush(anyList())).willAnswer(call -> {
			List<WorkSchedule> items = new ArrayList<>(call.getArgument(0));
			// 첫 저장(새 일정)에만 ID를 부여한다. 같은 테스트에서 prepare를 다시 호출하면 그 호출의 새 일정도 첫 저장이다.
			if (items.stream().allMatch(item -> ReflectionTestUtils.getField(item, "id") == null)) {
				for (int i = 0; i < items.size(); i++) ReflectionTestUtils.setField(items.get(i), "id", 301L + i);
				if (savedSchedules.isEmpty()) savedSchedules.addAll(items);
			}
			return items;
		});
		given(fixtures.insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any()))
			.willAnswer(call -> {
				inserted.add(new Object[] {call.getArgument(1), call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8)});
				return 1;
			});
		given(fixtures.findApplicationId(anyLong(), anyLong(), anyInt()))
			.willAnswer(call -> Optional.of(applicationId(call.getArgument(1), call.getArgument(2))));
		given(fixtures.insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).willReturn(1);
		given(fixtures.findAssignmentId(anyLong())).willAnswer(call -> Optional.of(assignmentId(call.getArgument(0))));
		given(revisions.save(any(WorkScheduleRevision.class))).willAnswer(call -> {
			WorkScheduleRevision revision = call.getArgument(0);
			var snapshot = new LinkedHashMap<String, Object>();
			for (String field : new String[] {"termsVersion", "revisionType", "actorId", "recordedAtUtc", "plannedStartAt", "plannedEndAt",
				"breakStartAt", "breakEndAt", "workDate"}) snapshot.put(field, ReflectionTestUtils.getField(revision, field));
			revisionSnapshots.add(snapshot);
			return revision;
		});
		given(fixtures.findScenarios(anyLong(), anyLong())).willReturn(List.of());
	}

	private static Object field(Object target, String name) { return ReflectionTestUtils.getField(target, name); }

	private static DemoMyApplicationFixtureRepository.ScenarioRow scenario(long id, long schedule, int round, int current,
			Integer applied, int latest, Long assignment, String status) {
		var v = new LinkedHashMap<String, Object>();
		v.put("applicationId", id); v.put("workScheduleId", schedule); v.put("applicationRound", round); v.put("currentFlag", current);
		v.put("appliedTermsVersion", applied); v.put("latestTermsVersion", latest); v.put("workDate", LocalDate.parse("2026-12-08"));
		v.put("assignmentId", assignment); v.put("displayStatus", status);
		return MyApplicationRows.stub(DemoMyApplicationFixtureRepository.ScenarioRow.class, v);
	}

	@Nested
	@DisplayName("권한·잠금·고용주 확인")
	class Guards {
		@Test
		@DisplayName("고용주·DB 역할 변경·회원 없음은 AccessDenied, 잠금·저장·조회 없음")
		void notWorker() {
			for (var found : List.of(Optional.of(member(WORKER, MemberRole.EMPLOYER)), Optional.of(memberWithRoleCode(WORKER, "SUSPENDED")),
					Optional.<Member>empty())) {
				given(members.findById(WORKER)).willReturn(found);
				assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOf(AccessDeniedException.class);
			}
			verifyNoInteractions(locks, postings, schedules, revisions, lookup, fixtures, changes, completion);
		}

		@Test
		@DisplayName("잠금 행을 얻지 못하면 503, 고용주 조회·저장 없음")
		void lockMissing() {
			given(locks.lockWorker(WORKER)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOfSatisfying(ApplicationException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
			verify(members, never()).findByLoginId(anyString());
			verifyNoInteractions(postings, fixtures, changes, completion);
		}

		@Test
		@DisplayName("demo-employer가 없거나 고용주가 아니면 409 DEMO_EMPLOYER_UNAVAILABLE, 업무 데이터 저장 전 중단")
		void employerUnavailable() {
			for (var found : List.of(Optional.<Member>empty(), Optional.of(member(EMPLOYER, MemberRole.WORKER)),
					Optional.of(memberWithRoleCode(EMPLOYER, "ADMIN")))) {
				given(members.findByLoginId("demo-employer")).willReturn(found);
				assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOfSatisfying(ApplicationException.class,
					e -> assertThat(e.code()).isEqualTo("DEMO_EMPLOYER_UNAVAILABLE"));
			}
			verifyNoInteractions(postings, schedules, revisions, lookup, fixtures, changes, completion);
		}

		@Test
		@DisplayName("순서: 역할 확인 → 잠금 행 준비 → 근로자 잠금 → 고용주 확인 → 오늘 시연 데이터 조회")
		void order() {
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(POSTING));
			service.prepare(WORKER);
			var order = inOrder(members, locks, lookup);
			order.verify(members).findById(WORKER);
			order.verify(locks).ensureExists(WORKER);
			order.verify(locks).lockWorker(WORKER);
			order.verify(members).findByLoginId("demo-employer");
			order.verify(lookup).findFixturePosting(EMPLOYER, MARKER);
		}
	}

	@Nested
	@DisplayName("GMT+9 날짜와 재사용")
	class Reuse {
		@Test
		@DisplayName("오늘 데이터가 있으면 created=false, 새 공고·일정·지원·약정·변경·완료·이력 저장 없음")
		void reuse() {
			given(lookup.findFixturePosting(EMPLOYER, MARKER)).willReturn(Optional.of(POSTING));
			given(fixtures.findScenarios(WORKER, POSTING)).willReturn(List.of(scenario(31, 301, 1, 1, 1, 1, null, "PENDING")));
			var response = service.prepare(WORKER);
			assertThat(response.created()).isFalse();
			assertThat(response.jobPostingId()).isEqualTo(POSTING);
			assertThat(response.scenarios()).extracting(DemoMyApplicationFixtureService.Scenario::applicationId).containsExactly(31L);
			verifyNoInteractions(postings, schedules, revisions, changes, completion);
			verify(fixtures, never()).insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any());
			verify(fixtures, never()).insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("GMT+9 경계: UTC 14:59:59.999999는 그날, 15:00:00은 다음 날 표식으로 조회")
		void boundary() {
			given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.of(POSTING));
			service(Instant.parse("2026-11-30T14:59:59.999999Z")).prepare(WORKER);
			service(Instant.parse("2026-11-30T15:00:00Z")).prepare(WORKER);
			verify(lookup).findFixturePosting(EMPLOYER, "[APP-04 시연·근로자 5·2026-11-30]");
			verify(lookup).findFixturePosting(EMPLOYER, "[APP-04 시연·근로자 5·2026-12-01]");
		}

		@Test
		@DisplayName("표식에 근로자 ID가 들어가 다른 근로자는 별도 공고를 준비")
		void perWorker() {
			given(members.findById(6L)).willReturn(Optional.of(member(6, MemberRole.WORKER)));
			given(locks.lockWorker(6L)).willReturn(Optional.of(BeanUtils.instantiateClass(ApplicationRequestLock.class)));
			given(lookup.findFixturePosting(anyLong(), anyString())).willReturn(Optional.of(POSTING));
			service.prepare(6L);
			verify(lookup).findFixturePosting(EMPLOYER, "[APP-04 시연·근로자 6·2026-12-01]");
			verify(fixtures).findScenarios(6L, POSTING);
		}
	}

	@Nested
	@DisplayName("새 준비 내용")
	class Create {
		@Test
		@DisplayName("가상 공고: 표식 제목·demo-employer·GMT+9·정원 3·가상 좌표, created=true")
		void posting() {
			var response = service.prepare(WORKER);
			assertThat(response.created()).isTrue();
			assertThat(response.jobPostingId()).isEqualTo(POSTING);
			var captor = ArgumentCaptor.forClass(JobPosting.class);
			verify(postings).saveAndFlush(captor.capture());
			var posting = captor.getValue();
			assertThat(field(posting, "title")).isEqualTo(MARKER);
			assertThat(field(posting, "employer")).isSameAs(employer);
			assertThat(field(posting, "timeZone")).isEqualTo("GMT+9");
			assertThat(field(posting, "headcount")).isEqualTo(3);
			assertThat((BigDecimal) field(posting, "latitude")).isEqualByComparingTo("37");
			assertThat((String) field(posting, "description")).contains("가상");
		}

		@Test
		@DisplayName("일정 9개: 오늘+7~+12, 오늘-2, 오늘-1, 오늘+13, 모두 09~17·휴게 12~13")
		void schedules() {
			service.prepare(WORKER);
			assertThat(savedSchedules).extracting(s -> field(s, "workDate")).containsExactly(TODAY.plusDays(7), TODAY.plusDays(8),
				TODAY.plusDays(9), TODAY.plusDays(10), TODAY.plusDays(11), TODAY.plusDays(12), TODAY.minusDays(2), TODAY.minusDays(1),
				TODAY.plusDays(13));
			for (var schedule : savedSchedules) {
				var day = (LocalDate) field(schedule, "workDate");
				if (schedule == savedSchedules.get(1)) continue; // 조건 변경 사례는 아래에서 확인
				assertThat(field(schedule, "plannedStartAt")).isEqualTo(day.atTime(9, 0));
				assertThat(field(schedule, "plannedEndAt")).isEqualTo(day.atTime(17, 0));
				assertThat(field(schedule, "breakStartAt")).isEqualTo(day.atTime(12, 0));
			}
		}

		@Test
		@DisplayName("지원 10건 = 현재 9건 + 과거 1건, 일정·상태·회차·현재 여부·지원 시각(지금-3일부터 1분 간격)")
		void applications() {
			service.prepare(WORKER);
			var first = NOW_UTC.minusDays(3);
			assertThat(inserted).hasSize(10);
			Object[][] expected = {
				{301L, "PENDING", 1, 1}, {302L, "PENDING", 1, 1}, {303L, "NOT_SELECTED", 1, 1}, {304L, "PENDING", 1, 1},
				{305L, "SELECTED", 1, 1}, {306L, "SELECTED", 1, 1}, {307L, "SELECTED", 1, 1}, {308L, "SELECTED", 1, 1},
				{309L, "NOT_SELECTED", 1, 0}, {309L, "PENDING", 2, 1}};
			for (int i = 0; i < expected.length; i++) {
				var row = inserted.get(i);
				assertThat(new Object[] {row[0], row[1], row[2], row[3]}).as("지원 " + i).containsExactly(expected[i]);
				assertThat(row[4]).as("지원 시각 " + i).isEqualTo(first.plusMinutes(i));
			}
			assertThat(inserted.stream().filter(row -> (int) row[3] == 1).count()).isEqualTo(9);
			verify(fixtures, times(10)).insertApplication(eq(WORKER), anyLong(), eq(POSTING), eq(EMPLOYER), eq(MARKER),
				anyString(), anyInt(), anyInt(), any());
		}

		@Test
		@DisplayName("재지원: 같은 일정의 과거 NOT_SELECTED 1회차(현재 아님)가 먼저, 현재 PENDING 2회차가 나중")
		void reapply() {
			service.prepare(WORKER);
			var sameSchedule = inserted.stream().filter(row -> (long) row[0] == 309L).toList();
			assertThat(sameSchedule).hasSize(2);
			assertThat(sameSchedule.get(0)[1]).isEqualTo("NOT_SELECTED");
			assertThat(sameSchedule.get(0)[3]).isEqualTo(0);
			assertThat(sameSchedule.get(1)[2]).isEqualTo(2);
			assertThat(((LocalDateTime) sameSchedule.get(1)[4])).isAfter((LocalDateTime) sameSchedule.get(0)[4]);
		}

		@Test
		@DisplayName("모집 취소: 넷째 일정만 REMOVED, 그 지원은 PENDING 그대로(미선정으로 덮지 않음), 확정 근무 없음")
		void recruitmentCancelled() {
			service.prepare(WORKER);
			assertThat(savedSchedules).extracting(s -> field(s, "status").toString())
				.containsExactly("OPEN", "OPEN", "OPEN", "REMOVED", "OPEN", "OPEN", "OPEN", "OPEN", "OPEN");
			assertThat(inserted.get(3)[1]).isEqualTo("PENDING");
			verify(fixtures, never()).insertAssignment(eq(applicationId(304, 1)), anyLong(), anyLong(), anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("조건 변경: 둘째 일정만 v1 baseline(09~17) 저장 후 v2 updated(10~18, 고용주) 저장, 지원 당시 버전은 SQL에서 1")
		void conditionChange() {
			service.prepare(WORKER);
			assertThat(revisionSnapshots).hasSize(2);
			var baseline = revisionSnapshots.get(0);
			var updated = revisionSnapshots.get(1);
			var day = TODAY.plusDays(8);
			assertThat(baseline).containsEntry("termsVersion", 1).containsEntry("actorId", null)
				.containsEntry("plannedStartAt", day.atTime(9, 0)).containsEntry("plannedEndAt", day.atTime(17, 0))
				.containsEntry("recordedAtUtc", NOW_UTC);
			assertThat(baseline.get("revisionType").toString()).isEqualTo("BASELINE");
			assertThat(updated).containsEntry("termsVersion", 2).containsEntry("actorId", EMPLOYER)
				.containsEntry("plannedStartAt", day.atTime(10, 0)).containsEntry("plannedEndAt", day.atTime(18, 0))
				.containsEntry("breakStartAt", day.atTime(12, 0));
			assertThat(updated.get("revisionType").toString()).isEqualTo("UPDATED");
			assertThat(savedSchedules).extracting(s -> field(s, "termsVersion")).containsExactly(1, 2, 1, 1, 1, 1, 1, 1, 1);
		}

		@Test
		@DisplayName("확정 약정: SELECTED 4건의 실제 새 지원 ID에만 연결, 확정 시각 = 첫 지원+1시간, 공고·고용주·표식 범위")
		void assignments() {
			service.prepare(WORKER);
			var confirmedAt = NOW_UTC.minusDays(3).plusHours(1);
			for (long schedule : new long[] {305, 306, 307, 308}) {
				verify(fixtures).insertAssignment(applicationId(schedule, 1), WORKER, POSTING, EMPLOYER, MARKER, confirmedAt);
			}
			verify(fixtures, times(4)).insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("확정 후 취소: 여섯째 일정의 새 약정만 WorkChangeRecorder에 CANCELLED·적용 시각 null·농장 사정으로 위임")
		void cancelled() {
			service.prepare(WORKER);
			verify(changes).recordValidatedChange(eq(EMPLOYER), eq(assignmentId(applicationId(306, 1))), eq(WorkChange.Type.CANCELLED),
				isNull(), eq(WorkChange.Reason.FARM_CIRCUMSTANCES), isNull(), org.mockito.ArgumentMatchers.contains("APP-04 시연"));
		}

		@Test
		@DisplayName("조기 종료: 과거 근무일(오늘-2) 14:00+09:00, 근무 구간 09~17 안이고 현재보다 과거, 기상(RAIN)으로 위임")
		void earlyEnded() {
			service.prepare(WORKER);
			var captor = ArgumentCaptor.forClass(OffsetDateTime.class);
			verify(changes).recordValidatedChange(eq(EMPLOYER), eq(assignmentId(applicationId(307, 1))), eq(WorkChange.Type.EARLY_ENDED),
				captor.capture(), eq(WorkChange.Reason.WEATHER), eq(WorkChange.WeatherReason.RAIN), anyString());
			var end = captor.getValue();
			assertThat(end).isEqualTo(TODAY.minusDays(2).atTime(14, 0).atOffset(ZoneOffset.ofHours(9)));
			assertThat(end.toLocalTime()).isAfter(java.time.LocalTime.of(9, 0)).isBefore(java.time.LocalTime.of(17, 0));
			assertThat(end.toInstant()).isBefore(NOW);
			assertThat(end.getNano() % 1000).isZero();
		}

		@Test
		@DisplayName("완료: 과거 날짜(오늘-1, 종료 17:00+09:00 < 현재)의 새 약정만 기존 완료 서비스에 위임")
		void completed() {
			service.prepare(WORKER);
			verify(completion).complete(EMPLOYER, assignmentId(applicationId(308, 1)));
			verify(completion, times(1)).complete(anyLong(), anyLong());
			assertThat(TODAY.minusDays(1).atTime(17, 0).atOffset(ZoneOffset.ofHours(9)).toInstant()).isBefore(NOW);
		}

		@Test
		@DisplayName("같은 근무를 취소·조기 종료·완료로 중복 준비하지 않음(확정 사례는 어느 쪽에도 넘기지 않음)")
		void distinct() {
			service.prepare(WORKER);
			var changed = ArgumentCaptor.forClass(Long.class);
			verify(changes, times(2)).recordValidatedChange(anyLong(), changed.capture(), any(), any(), any(), any(), anyString());
			var completed = ArgumentCaptor.forClass(Long.class);
			verify(completion).complete(anyLong(), completed.capture());
			assertThat(changed.getAllValues()).doesNotHaveDuplicates().doesNotContain(completed.getValue(),
				assignmentId(applicationId(305, 1)));
			assertThat(completed.getValue()).isNotEqualTo(assignmentId(applicationId(305, 1)));
		}

		@Test
		@DisplayName("저장 순서: 공고 → 일정 → 지원·약정·변경·완료 → 조건 이력 2건 → 일정 저장 → 사례 조회")
		void order() {
			service.prepare(WORKER);
			var order = inOrder(postings, schedules, fixtures, changes, completion, revisions);
			order.verify(postings).saveAndFlush(any());
			order.verify(schedules).saveAllAndFlush(anyList());
			order.verify(fixtures).insertApplication(anyLong(), eq(301L), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any());
			order.verify(changes).recordValidatedChange(anyLong(), anyLong(), eq(WorkChange.Type.CANCELLED), any(), any(), any(), anyString());
			order.verify(changes).recordValidatedChange(anyLong(), anyLong(), eq(WorkChange.Type.EARLY_ENDED), any(), any(), any(), anyString());
			order.verify(completion).complete(anyLong(), anyLong());
			order.verify(fixtures).insertApplication(anyLong(), eq(309L), anyLong(), anyLong(), anyString(), eq("PENDING"), eq(2), anyInt(), any());
			order.verify(revisions, times(2)).save(any());
			order.verify(schedules).saveAllAndFlush(anyList());
			order.verify(fixtures).findScenarios(WORKER, POSTING);
		}
	}

	@Nested
	@DisplayName("중간 실패")
	class Failures {
		@Test
		@DisplayName("지원 INSERT 처리 건수가 1이 아니면 503, 이후 저장·변경·완료 없음")
		void insertCount() {
			given(fixtures.insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any()))
				.willReturn(0);
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOfSatisfying(ApplicationException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
			verify(fixtures, times(1)).insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any());
			verifyNoInteractions(changes, completion, revisions);
		}

		@Test
		@DisplayName("INSERT 2건 처리도 503(1건 초과를 성공으로 보지 않음)")
		void insertTwo() {
			given(fixtures.insertApplication(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any()))
				.willReturn(2);
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOf(ApplicationException.class);
		}

		@Test
		@DisplayName("저장한 지원 ID를 다시 찾지 못하면 503")
		void idMissing() {
			given(fixtures.findApplicationId(anyLong(), anyLong(), anyInt())).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOfSatisfying(ApplicationException.class,
				e -> assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
		}

		@Test
		@DisplayName("약정 INSERT 0건·약정 ID 누락은 503, 변경·완료 없음")
		void assignmentFailure() {
			given(fixtures.insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).willReturn(0);
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOf(ApplicationException.class);
			given(fixtures.insertAssignment(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), any())).willReturn(1);
			given(fixtures.findAssignmentId(anyLong())).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.prepare(WORKER)).isInstanceOf(ApplicationException.class);
			verifyNoInteractions(changes, completion);
		}

		@Test
		@DisplayName("변경 기록 위임 중 예외는 그대로 전파, 완료·조건 이력·사례 조회 없음 (실제 롤백은 확인하지 않음)")
		void changeFailure() {
			var failure = new IllegalStateException("change failed");
			willThrow(failure).given(changes).recordValidatedChange(anyLong(), anyLong(), any(), any(), any(), any(), anyString());
			assertThatThrownBy(() -> service.prepare(WORKER)).isSameAs(failure);
			verifyNoInteractions(completion, revisions);
			verify(fixtures, never()).findScenarios(anyLong(), anyLong());
		}

		@Test
		@DisplayName("완료 위임 중 예외도 그대로 전파, 이후 재지원·조건 이력 저장 없음")
		void completionFailure() {
			var failure = new IllegalStateException("completion failed");
			willThrow(failure).given(completion).complete(anyLong(), anyLong());
			assertThatThrownBy(() -> service.prepare(WORKER)).isSameAs(failure);
			verifyNoInteractions(revisions);
			verify(fixtures, never()).insertApplication(anyLong(), eq(309L), anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyInt(), any());
		}
	}

	@Nested
	@DisplayName("응답 사례")
	class Response {
		@Test
		@DisplayName("사례 이름·ID·회차·현재 여부·확정 근무 ID·변경 여부는 조회 결과를 그대로 반영")
		void labels() {
			given(fixtures.findScenarios(WORKER, POSTING)).willReturn(List.of(
				scenario(3011, 301, 1, 1, 1, 1, null, "PENDING"),
				scenario(3021, 302, 1, 1, 1, 2, null, "PENDING"),
				scenario(3031, 303, 1, 1, 1, 1, null, "NOT_SELECTED"),
				scenario(3041, 304, 1, 1, 1, 1, null, "CANCELLED"),
				scenario(3051, 305, 1, 1, 1, 1, 53051L, "CONFIRMED"),
				scenario(3061, 306, 1, 1, 1, 1, 53061L, "CANCELLED"),
				scenario(3071, 307, 1, 1, 1, 1, 53071L, "EARLY_ENDED"),
				scenario(3081, 308, 1, 1, 1, 1, 53081L, "COMPLETED"),
				scenario(3092, 309, 2, 1, 1, 1, null, "PENDING"),
				scenario(3091, 309, 1, 0, 1, 1, null, "NOT_SELECTED")));
			var scenarios = service.prepare(WORKER).scenarios();
			assertThat(scenarios).extracting(DemoMyApplicationFixtureService.Scenario::label).containsExactly(
				"지원 대기", "지원 후 조건 변경", "미선정", "모집 취소", "근무 확정", "확정 후 취소", "조기 종료", "근무 완료",
				"재지원의 현재 회차", "재지원의 과거 회차");
			assertThat(scenarios.get(1).conditionsChanged()).isTrue();
			assertThat(scenarios.get(0).conditionsChanged()).isFalse();
			assertThat(scenarios.get(5).assignmentId()).isEqualTo(53061L);
			assertThat(scenarios.get(9).current()).isFalse();
			assertThat(scenarios.get(9).applicationRound()).isEqualTo(1);
			assertThat(scenarios.get(8).current()).isTrue();
			assertThat(scenarios).filteredOn(DemoMyApplicationFixtureService.Scenario::current).hasSize(9);
		}

		@Test
		@DisplayName("분류할 수 없는 상태는 '상태 확인 필요'(정상 확정으로 추측하지 않음), 지원 버전 null은 변경 아님")
		void unknown() {
			given(fixtures.findScenarios(WORKER, POSTING)).willReturn(List.of(scenario(1, 301, 1, 1, null, 3, null, "UNKNOWN")));
			var only = service.prepare(WORKER).scenarios().getFirst();
			assertThat(only.label()).isEqualTo("상태 확인 필요");
			assertThat(only.conditionsChanged()).isFalse();
		}
	}

	@Test
	@DisplayName("선언: prepare는 READ_COMMITTED 트랜잭션, 서비스·저장소·API·화면은 farm-demo 프로필 (선언만 확인)")
	void declarations() throws Exception {
		var tx = DemoMyApplicationFixtureService.class.getMethod("prepare", long.class).getAnnotation(Transactional.class);
		assertThat(tx).isNotNull();
		assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(tx.readOnly()).isFalse();
		for (Class<?> type : List.of(DemoMyApplicationFixtureService.class, DemoMyApplicationFixtureRepository.class,
				DemoMyApplicationFixtureApi.class, DemoMyApplicationPage.class, DemoJobApplicationErrorHandler.class)) {
			assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
		}
	}
}

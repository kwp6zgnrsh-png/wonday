package com.wonday.farm.work.service;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.application.testsupport.ApplicationTestData.memberWithRoleCode;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.EMPLOYER;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.POSTING;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.SCHEDULE;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.contact;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.row;
import static com.wonday.farm.work.testsupport.WorkApplicantRows.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import com.wonday.farm.application.service.ApplicationConditionMapper;
import com.wonday.farm.application.testsupport.MyApplicationRows;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import com.wonday.farm.work.dto.WorkApplicantResponses;
import com.wonday.farm.work.exception.WorkApplicantException;
import com.wonday.farm.work.repository.WorkApplicantRepository;
import com.wonday.farm.work.validation.WorkApplicantCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 서비스 + 실제 APP-04 매퍼·코드 목록, 저장소는 mock.
// mock이 돌려준 순서는 SQL 정렬 검증이 아니다. 집계·마스킹·정렬 SQL의 실제 결과는 확인하지 않는다.
@DisplayName("WORK-01 지원자 조회 서비스 (저장소 mock)")
class WorkApplicantQueryServiceTest {
	private final MemberRepository members = mock(MemberRepository.class);
	private final WorkApplicantRepository repository = mock(WorkApplicantRepository.class);
	private final WorkScheduleRepository schedules = mock(WorkScheduleRepository.class);
	private final WorkApplicantQueryService service = new WorkApplicantQueryService(members, repository, schedules,
		new ApplicationConditionMapper(new AgriculturalCodeCatalog()));

	@BeforeEach
	void owned() {
		given(members.findById(EMPLOYER)).willReturn(Optional.of(member(EMPLOYER, MemberRole.EMPLOYER)));
		given(repository.findOwnedPosting(EMPLOYER, POSTING)).willReturn(Optional.of(POSTING));
		given(repository.findOwnedSchedule(EMPLOYER, POSTING, SCHEDULE)).willReturn(Optional.of(schedule("OPEN", 3)));
		given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
		given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(1L);
	}

	private static WorkApplicantRepository.Row app(long id) {
		return row(v -> { v.put("applicationId", id); v.put("sortAppliedAt", LocalDateTime.parse("2026-12-01T00:00").plusMinutes(id)); });
	}
	private static List<WorkApplicantRepository.Row> apps(long... ids) { return LongStream.of(ids).mapToObj(WorkApplicantQueryServiceTest::app).toList(); }
	private static void code(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(WorkApplicantException.class, e -> assertThat(e.code()).isEqualTo(code));
	}
	private WorkApplicantResponses.Page list(String status, String limit, String cursor) {
		return service.list(EMPLOYER, POSTING, SCHEDULE, status, limit, cursor);
	}

	@Test
	@DisplayName("트랜잭션 선언: 목록·상세 REPEATABLE_READ readOnly, 연락처는 READ_COMMITTED readOnly (선언만 확인)")
	void transactional() throws Exception {
		var type = WorkApplicantQueryService.class.getAnnotation(Transactional.class);
		assertThat(type.readOnly()).isTrue();
		assertThat(type.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
		var contact = WorkApplicantQueryService.class.getMethod("contact", long.class, long.class, long.class, long.class)
			.getAnnotation(Transactional.class);
		assertThat(contact.isolation()).isEqualTo(Isolation.READ_COMMITTED);
		assertThat(contact.readOnly()).isTrue();
	}

	@Nested
	@DisplayName("역할·소유권")
	class Ownership {
		@Test
		@DisplayName("현재 DB 역할이 EMPLOYER가 아니면(근로자·모르는 코드·회원 없음) 목록·상세·연락처 모두 AccessDenied, 저장소 조회 없음")
		void notEmployer() {
			for (var found : List.of(Optional.of(member(EMPLOYER, MemberRole.WORKER)), Optional.of(memberWithRoleCode(EMPLOYER, "ADMIN")),
					Optional.<com.wonday.farm.member.Member>empty())) {
				given(members.findById(EMPLOYER)).willReturn(found);
				assertThatThrownBy(() -> list(null, null, null)).isInstanceOf(AccessDeniedException.class);
				assertThatThrownBy(() -> service.detail(EMPLOYER, POSTING, SCHEDULE, 501)).isInstanceOf(AccessDeniedException.class);
				assertThatThrownBy(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 501)).isInstanceOf(AccessDeniedException.class);
			}
			verifyNoInteractions(repository, schedules);
		}

		@Test
		@DisplayName("다른 고용주 공고(소유자 조건 조회 빈 결과)는 404 JOB_POSTING_NOT_FOUND, 일정·지원 조회 없음")
		void otherPosting() {
			given(repository.findOwnedPosting(EMPLOYER, 999)).willReturn(Optional.empty());
			code(() -> service.list(EMPLOYER, 999, SCHEDULE, null, null, null), "JOB_POSTING_NOT_FOUND");
			code(() -> service.detail(EMPLOYER, 999, SCHEDULE, 501), "JOB_POSTING_NOT_FOUND");
			code(() -> service.contact(EMPLOYER, 999, SCHEDULE, 501), "JOB_POSTING_NOT_FOUND");
			verify(repository, never()).findOwnedSchedule(anyLong(), anyLong(), anyLong());
			verify(repository, never()).findContact(anyLong(), anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("공고에 속하지 않는 일정은 404 WORK_SCHEDULE_NOT_FOUND, 지원 조회 없음")
		void otherSchedule() {
			given(repository.findOwnedSchedule(EMPLOYER, POSTING, 999)).willReturn(Optional.empty());
			code(() -> service.list(EMPLOYER, POSTING, 999, null, null, null), "WORK_SCHEDULE_NOT_FOUND");
			code(() -> service.detail(EMPLOYER, POSTING, 999, 501), "WORK_SCHEDULE_NOT_FOUND");
			code(() -> service.contact(EMPLOYER, POSTING, 999, 501), "WORK_SCHEDULE_NOT_FOUND");
			verify(repository, never()).findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
			verify(repository, never()).findCurrentOwned(anyLong(), anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("세션 회원 ID를 고용주 ID로 모든 저장소 호출에 전달(공고·일정·지원 ID 포함)")
		void arguments() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row()));
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(contact(501, "PENDING", 1, "01000001001")));
			list(null, null, null);
			service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
			service.contact(EMPLOYER, POSTING, SCHEDULE, 501);
			verify(repository, times(3)).findOwnedPosting(EMPLOYER, POSTING);
			verify(repository, times(3)).findOwnedSchedule(EMPLOYER, POSTING, SCHEDULE);
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, 21);
			verify(repository).findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501);
			verify(repository).findContact(EMPLOYER, POSTING, SCHEDULE, 501);
		}

		@ParameterizedTest
		@ValueSource(strings = {"OPEN", "PAUSED", "CLOSED", "REMOVED"})
		@DisplayName("OPEN·PAUSED·CLOSED·REMOVED(지난 근무일 포함) 모두 조회 허용, 모집 상태 그대로 응답")
		void anyRecruitmentStatus(String status) {
			given(repository.findOwnedSchedule(EMPLOYER, POSTING, SCHEDULE)).willReturn(Optional.of(MyApplicationRows.stub(
				WorkApplicantRepository.Schedule.class, java.util.Map.of("workScheduleId", SCHEDULE,
					"workDate", java.time.LocalDate.parse("2020-01-01"), "recruitmentStatus", status, "headcount", 3))));
			var page = list(null, null, null);
			assertThat(page.recruitmentStatus()).isEqualTo(status);
			assertThat(page.workDate()).isEqualTo(java.time.LocalDate.parse("2020-01-01"));
		}

		@ParameterizedTest
		@ValueSource(longs = {0, -1})
		@DisplayName("서비스 직접 호출에서도 공고·일정·지원 ID 0·음수는 INVALID_REQUEST")
		void nonPositive(long id) {
			code(() -> service.list(EMPLOYER, id, SCHEDULE, null, null, null), "INVALID_REQUEST");
			code(() -> service.list(EMPLOYER, POSTING, id, null, null, null), "INVALID_REQUEST");
			code(() -> service.detail(EMPLOYER, POSTING, SCHEDULE, id), "INVALID_REQUEST");
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, id), "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("필터·개수")
	class Inputs {
		@ParameterizedTest
		@ValueSource(strings = {"PENDING", "ALL", "SELECTED", "NOT_SELECTED"})
		@DisplayName("허용 필터는 그대로 전달(생략은 PENDING), 현재 회차 쿼리만 사용")
		void filters(String filter) {
			list(filter, null, null);
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, filter, null, null, 21);
		}

		@Test
		@DisplayName("필터 생략 → PENDING, limit 생략 → 20(조회 21건)")
		void defaults() {
			list(null, null, null);
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, 21);
		}

		@ParameterizedTest
		@ValueSource(strings = {"", "pending", "CONFIRMED", "CANCELLED", "UNKNOWN", "ALL ", "PENDING,SELECTED"})
		@DisplayName("빈 값·소문자·APP-04 표시 상태·공백·조합 필터는 INVALID_REQUEST")
		void badFilter(String filter) {
			code(() -> list(filter, null, null), "INVALID_REQUEST");
			verify(repository, never()).findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
		}

		@ParameterizedTest
		@ValueSource(strings = {"1", "100"})
		@DisplayName("limit 1·100 허용, 조회는 limit+1")
		void bounds(String limit) {
			list(null, limit, null);
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, Integer.parseInt(limit) + 1);
		}

		@ParameterizedTest
		@ValueSource(strings = {"0", "-1", "101", "999", "1000", "1.5", "abc", "", " ", "+1", "1e1"})
		@DisplayName("limit 0·음수·101 이상·소수·문자·빈 값·부호·지수 표기는 INVALID_REQUEST")
		void badLimit(String limit) {
			code(() -> list(null, limit, null), "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("페이지·커서·집계")
	class Paging {
		@Test
		@DisplayName("빈 결과: items=[]·nextCursor=null·hasMore=false, 정원·확정·남은 자리는 일정 전체 집계")
		void empty() {
			var page = list(null, null, null);
			assertThat(page.items()).isEmpty();
			assertThat(page.nextCursor()).isNull();
			assertThat(page.hasMore()).isFalse();
			assertThat(page.jobPostingId()).isEqualTo(POSTING);
			assertThat(page.workScheduleId()).isEqualTo(SCHEDULE);
			assertThat(page.headcount()).isEqualTo(3);
			assertThat(page.confirmedCount()).isEqualTo(1);
			assertThat(page.remainingSlots()).isEqualTo(2);
		}

		@Test
		@DisplayName("size+1건 → size건만 반환·hasMore=true·다음 커서는 마지막 표시 항목(같은 고용주·공고·일정·필터)")
		void more() {
			given(repository.findCurrent(EMPLOYER, POSTING, SCHEDULE, "ALL", null, null, 3)).willReturn(apps(10, 20, 30));
			var page = list("ALL", "2", null);
			assertThat(page.items()).extracting(WorkApplicantResponses.Item::applicationId).containsExactly(10L, 20L);
			assertThat(page.hasMore()).isTrue();
			assertThat(page.nextCursor()).isEqualTo(WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "ALL", 20));
		}

		@Test
		@DisplayName("정확히 size건이면 마지막 페이지(nextCursor=null), 반환 개수는 요청 크기 이하")
		void last() {
			given(repository.findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, 3)).willReturn(apps(10, 20));
			var page = list(null, "2", null);
			assertThat(page.items()).hasSize(2);
			assertThat(page.nextCursor()).isNull();
			assertThat(page.hasMore()).isFalse();
		}

		@Test
		@DisplayName("여러 페이지: 1쪽 커서 → 앵커를 소유권 범위로 다시 조회 → 그 정렬 시각·ID를 다음 조회 경계로 전달 → 2쪽 커서")
		void multiplePages() {
			given(repository.findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", null, null, 2)).willReturn(apps(10, 20));
			var first = list(null, "1", null);
			var anchor = app(10);
			given(repository.findOwnedAnchor(EMPLOYER, POSTING, SCHEDULE, 10)).willReturn(Optional.of(anchor));
			given(repository.findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", anchor.getSortAppliedAt(), 10L, 2)).willReturn(apps(20, 30));
			var second = list(null, "1", first.nextCursor());
			assertThat(second.items()).extracting(WorkApplicantResponses.Item::applicationId).containsExactly(20L);
			assertThat(second.nextCursor()).isEqualTo(WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "PENDING", 20));
			verify(repository).findOwnedAnchor(EMPLOYER, POSTING, SCHEDULE, 10);
		}

		@Test
		@DisplayName("앵커가 과거 회차로 바뀌어도 저장된 원래 지원 시각을 경계로 사용(거부하지 않음)")
		void anchorBecamePast() {
			var anchor = row(v -> { v.put("applicationId", 10L); v.put("currentFlag", 0);
				v.put("sortAppliedAt", LocalDateTime.parse("2026-11-01T01:02:03.000004")); });
			given(repository.findOwnedAnchor(EMPLOYER, POSTING, SCHEDULE, 10)).willReturn(Optional.of(anchor));
			list(null, null, WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "PENDING", 10));
			verify(repository).findCurrent(EMPLOYER, POSTING, SCHEDULE, "PENDING", LocalDateTime.parse("2026-11-01T01:02:03.000004"), 10L, 21);
		}

		@Test
		@DisplayName("본인 범위에 없는 앵커(빈 결과)는 INVALID_CURSOR, 목록 조회 없음")
		void anchorOutOfScope() {
			given(repository.findOwnedAnchor(EMPLOYER, POSTING, SCHEDULE, 77)).willReturn(Optional.empty());
			code(() -> list(null, null, WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "PENDING", 77)), "INVALID_CURSOR");
			verify(repository, never()).findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("다른 고용주·공고·일정·필터의 커서는 INVALID_CURSOR, 앵커 조회 없음")
		void foreignCursor() {
			given(repository.findOwnedPosting(EMPLOYER, 102)).willReturn(Optional.of(102L));
			given(repository.findOwnedSchedule(EMPLOYER, 102, SCHEDULE)).willReturn(Optional.of(schedule("OPEN", 3)));
			code(() -> list(null, null, WorkApplicantCursor.encode(11, POSTING, SCHEDULE, "PENDING", 10)), "INVALID_CURSOR");
			code(() -> service.list(EMPLOYER, 102, SCHEDULE, null, null, WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "PENDING", 10)), "INVALID_CURSOR");
			code(() -> list(null, null, WorkApplicantCursor.encode(EMPLOYER, POSTING, 202, "PENDING", 10)), "INVALID_CURSOR");
			code(() -> list("ALL", null, WorkApplicantCursor.encode(EMPLOYER, POSTING, SCHEDULE, "PENDING", 10)), "INVALID_CURSOR");
			code(() -> list(null, null, "%%"), "INVALID_CURSOR");
			verify(repository, never()).findOwnedAnchor(anyLong(), anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("집계는 필터와 무관: PENDING·ALL·SELECTED 모두 같은 일정 전체 집계(countOccupyingAssignments(일정)) 사용")
		void aggregateIgnoresFilter() {
			for (String filter : new String[] {"PENDING", "ALL", "SELECTED", "NOT_SELECTED"}) {
				var page = list(filter, null, null);
				assertThat(page.confirmedCount()).as(filter).isEqualTo(1);
				assertThat(page.remainingSlots()).as(filter).isEqualTo(2);
			}
			verify(schedules, times(4)).countOccupyingAssignments(SCHEDULE);
		}

		@Test
		@DisplayName("남은 자리 = max(정원-확정, 0): 확정 초과(5/3)여도 음수 아님, 정원 꽉 참은 0")
		void remainingNeverNegative() {
			given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(5L);
			var over = list(null, null, null);
			assertThat(over.confirmedCount()).isEqualTo(5);
			assertThat(over.remainingSlots()).isZero();
			given(schedules.countOccupyingAssignments(SCHEDULE)).willReturn(3L);
			assertThat(list(null, null, null).remainingSlots()).isZero();
		}
	}

	@Nested
	@DisplayName("목록 항목")
	class Items {
		@Test
		@DisplayName("이름 전체·마스킹 번호·연락처 상태·회차·지원 상태·근무 상태·+09:00 지원 시각·버전 비교 전달")
		void fields() {
			given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(row(v -> {
				v.put("latestTermsVersion", 4); v.put("applicationRound", 2); })));
			var item = list(null, null, null).items().getFirst();
			assertThat(item.name()).isEqualTo("시연 근로자 1");
			assertThat(item.maskedPhone()).isEqualTo("010-****-1001");
			assertThat(item.phoneState()).isEqualTo("AVAILABLE");
			assertThat(item.applicationRound()).isEqualTo(2);
			assertThat(item.status()).isEqualTo("PENDING");
			assertThat(item.workStatus()).isEqualTo("PENDING");
			assertThat(item.appliedAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T15:00:00.123456+09:00"));
			assertThat(item.appliedTimeBasis()).isEqualTo("UTC");
			assertThat(item.conditionComparisonStatus()).isEqualTo("CHANGED");
			assertThat(item.canViewContact()).isTrue();
		}

		@Test
		@DisplayName("canViewContact: 현재 PENDING·SELECTED만 true, NOT_SELECTED·UNKNOWN 상태는 false, 과거 행은 false")
		void canViewContact() {
			given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(
				row(v -> v.put("applicationStatus", "PENDING")), row(v -> v.put("applicationStatus", "SELECTED")),
				row(v -> v.put("applicationStatus", "NOT_SELECTED")), row(v -> v.put("applicationStatus", "WITHDRAWN")),
				row(v -> { v.put("applicationStatus", "PENDING"); v.put("currentFlag", 0); })));
			assertThat(list("ALL", null, null).items()).extracting(WorkApplicantResponses.Item::canViewContact)
				.containsExactly(true, true, false, false, false);
		}

		@Test
		@DisplayName("미등록·형식 오류 번호는 마스킹 null과 상태를 그대로 전달(가상 번호로 보완하지 않음)")
		void phoneStates() {
			given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(
				row(v -> { v.put("maskedPhone", null); v.put("phoneState", "NOT_REGISTERED"); }),
				row(v -> { v.put("maskedPhone", null); v.put("phoneState", "INVALID_FORMAT"); })));
			var items = list(null, null, null).items();
			assertThat(items).extracting(WorkApplicantResponses.Item::maskedPhone).containsOnlyNulls();
			assertThat(items).extracting(WorkApplicantResponses.Item::phoneState).containsExactly("NOT_REGISTERED", "INVALID_FORMAT");
			assertThat(items).allSatisfy(item -> assertThat(item.canViewContact()).isTrue());
		}

		@Test
		@DisplayName("UTC 누락 기존 행: appliedAt=null·legacyAppliedAt 원본·LEGACY_LOCAL_UNKNOWN_ZONE, 확정 근무 ID·근무 상태 전달")
		void legacyAndAssignment() {
			given(repository.findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(row(v -> {
				v.put("appliedAtUtc", null); v.put("legacyAppliedAt", LocalDateTime.parse("2025-05-01T08:30"));
				MyApplicationRows.assignment(v); v.put("displayStatus", "EARLY_ENDED"); })));
			var item = list("ALL", null, null).items().getFirst();
			assertThat(item.appliedAt()).isNull();
			assertThat(item.legacyAppliedAt()).isEqualTo(LocalDateTime.parse("2025-05-01T08:30"));
			assertThat(item.appliedTimeBasis()).isEqualTo("LEGACY_LOCAL_UNKNOWN_ZONE");
			assertThat(item.assignmentId()).isEqualTo(700L);
			assertThat(item.status()).isEqualTo("SELECTED");
			assertThat(item.workStatus()).isEqualTo("EARLY_ENDED");
		}
	}

	@Nested
	@DisplayName("상세·조건 비교")
	class Detail {
		@Test
		@DisplayName("현재 회차만 조회: 없음·다른 날짜·과거 회차(현재 조건 쿼리 빈 결과)는 404 APPLICATION_NOT_FOUND")
		void notFound() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 401)).willReturn(Optional.empty());
			code(() -> service.detail(EMPLOYER, POSTING, SCHEDULE, 401), "APPLICATION_NOT_FOUND");
			verify(repository, never()).findOwnedAnchor(anyLong(), anyLong(), anyLong(), anyLong());
			verify(repository, never()).findContact(anyLong(), anyLong(), anyLong(), anyLong());
		}

		@Test
		@DisplayName("조건 변경 사례: v1 이력 vs 최신 v2 → CHANGED·변경 시각 2건·좌표·작업 내용 확인 불가·비교 불완전, 최신 값으로 채우지 않음")
		void changed() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row(v -> {
				v.put("appliedTermsVersion", 1); v.put("latestTermsVersion", 2); MyApplicationRows.revision(v);
				v.put("plannedStartAt", LocalDateTime.parse("2026-12-10T10:00")); v.put("plannedEndAt", LocalDateTime.parse("2026-12-10T18:00")); })));
			var d = service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(d.conditionComparisonStatus()).isEqualTo("CHANGED");
			assertThat(d.appliedTermsVersion()).isEqualTo(1);
			assertThat(d.latestTermsVersion()).isEqualTo(2);
			assertThat(d.changedFields()).containsExactly("plannedStartAt", "plannedEndAt");
			assertThat(d.unavailableComparisonFields()).containsExactly("latitude", "longitude", "description");
			assertThat(d.comparisonComplete()).isFalse();
			assertThat(d.appliedConditions().latitude()).isNull();
			assertThat(d.appliedConditions().description()).isNull();
			assertThat(d.appliedConditions().plannedStartAt()).isEqualTo(OffsetDateTime.parse("2026-12-10T09:00+09:00"));
			assertThat(d.latestConditions().plannedStartAt()).isEqualTo(OffsetDateTime.parse("2026-12-10T10:00+09:00"));
			assertThat(d.latestConditions().latitude()).isEqualByComparingTo("37");
		}

		@Test
		@DisplayName("같은 버전·이력 없음 → UNCHANGED·비교 완료, 지원 버전 null → UNKNOWN·지원 당시 UNAVAILABLE")
		void unchangedAndUnknown() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row()));
			var same = service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(same.conditionComparisonStatus()).isEqualTo("UNCHANGED");
			assertThat(same.comparisonComplete()).isTrue();
			assertThat(same.changedFields()).isEmpty();
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 502)).willReturn(Optional.of(row(v -> v.put("appliedTermsVersion", null))));
			var unknown = service.detail(EMPLOYER, POSTING, SCHEDULE, 502);
			assertThat(unknown.conditionComparisonStatus()).isEqualTo("UNKNOWN");
			assertThat(unknown.appliedConditions().availability()).isEqualTo("UNAVAILABLE");
			assertThat(unknown.comparisonComplete()).isFalse();
			assertThat(unknown.appliedTermsVersion()).isNull();
		}

		@Test
		@DisplayName("확정 근무가 있으면 저장 약정을 별도 반환(최신 공고와 다른 값 유지), 없으면 assignment=null")
		void assignment() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row(MyApplicationRows::assignment)));
			var d = service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(d.assignment().assignmentId()).isEqualTo(700L);
			assertThat(d.assignment().agreedConditions().workLocation()).isEqualTo("약정 당시 작업지");
			assertThat(d.assignment().agreedConditions().dailyWage()).isEqualTo(110000L);
			assertThat(d.latestConditions().workLocation()).isEqualTo("가상 작업지");
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 502)).willReturn(Optional.of(row()));
			assertThat(service.detail(EMPLOYER, POSTING, SCHEDULE, 502).assignment()).isNull();
		}

		@Test
		@DisplayName("상세: 이름 전체·마스킹 번호만, 기존 UTC/legacy 시각 구분, 연락처 조회·연락처 쿼리 호출 없음")
		void identityAndTime() {
			given(repository.findCurrentOwned(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(row(v -> {
				v.put("appliedAtUtc", null); v.put("legacyAppliedAt", LocalDateTime.parse("2025-05-01T08:30")); })));
			var d = service.detail(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(d.name()).isEqualTo("시연 근로자 1");
			assertThat(d.maskedPhone()).isEqualTo("010-****-1001");
			assertThat(d.appliedAt()).isNull();
			assertThat(d.appliedTimeBasis()).isEqualTo("LEGACY_LOCAL_UNKNOWN_ZONE");
			assertThat(d.canViewContact()).isTrue();
			verify(repository, never()).findContact(anyLong(), anyLong(), anyLong(), anyLong());
		}
	}

	@Nested
	@DisplayName("전체 연락처")
	class Contact {
		private void stub(String status, int current, String phone) {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501)).willReturn(Optional.of(contact(501, status, current, phone)));
		}

		@ParameterizedTest
		@ValueSource(strings = {"PENDING", "SELECTED"})
		@DisplayName("현재 회차 PENDING·SELECTED는 최신 회원 번호를 정규화해 AVAILABLE로 반환")
		void allowed(String status) {
			stub(status, 1, "010-0000 1001");
			var c = service.contact(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(c.phone()).isEqualTo("01000001001");
			assertThat(c.phoneState()).isEqualTo("AVAILABLE");
			assertThat(c.name()).isEqualTo("시연 근로자 1");
			assertThat(c.workerId()).isEqualTo(123L);
		}

		@Test
		@DisplayName("NOT_SELECTED는 403 CONTACT_ACCESS_DENIED")
		void notSelected() {
			stub("NOT_SELECTED", 1, "01000001001");
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 501), "CONTACT_ACCESS_DENIED");
		}

		@ParameterizedTest
		@ValueSource(strings = {"PENDING", "SELECTED", "NOT_SELECTED"})
		@DisplayName("과거 회차는 상태와 관계없이 403 CONTACT_ACCESS_DENIED")
		void past(String status) {
			stub(status, 0, "01000001001");
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 501), "CONTACT_ACCESS_DENIED");
		}

		@Test
		@DisplayName("모르는 지원 상태도 403(허용 목록 방식)")
		void unknownStatus() {
			stub("WITHDRAWN", 1, "01000001001");
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 501), "CONTACT_ACCESS_DENIED");
		}

		@Test
		@DisplayName("해당 공고·일정에 없는 지원은 404 APPLICATION_NOT_FOUND")
		void missing() {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 999)).willReturn(Optional.empty());
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 999), "APPLICATION_NOT_FOUND");
		}

		@Test
		@DisplayName("목록 뒤 상태가 바뀌면 요청 시점 상태로 판단: 매 요청 저장소를 다시 조회(PENDING 200 → NOT_SELECTED 403)")
		void reevaluated() {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501))
				.willReturn(Optional.of(contact(501, "PENDING", 1, "01000001001")), Optional.of(contact(501, "NOT_SELECTED", 1, "01000001001")));
			assertThat(service.contact(EMPLOYER, POSTING, SCHEDULE, 501).phone()).isEqualTo("01000001001");
			code(() -> service.contact(EMPLOYER, POSTING, SCHEDULE, 501), "CONTACT_ACCESS_DENIED");
			verify(repository, times(2)).findContact(EMPLOYER, POSTING, SCHEDULE, 501);
			verify(repository, never()).findCurrent(anyLong(), anyLong(), anyLong(), anyString(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("번호가 바뀌면 새 번호(최신 회원정보) 사용")
		void latestPhone() {
			given(repository.findContact(EMPLOYER, POSTING, SCHEDULE, 501))
				.willReturn(Optional.of(contact(501, "PENDING", 1, "01000001001")), Optional.of(contact(501, "PENDING", 1, "01099998888")));
			assertThat(service.contact(EMPLOYER, POSTING, SCHEDULE, 501).phone()).isEqualTo("01000001001");
			assertThat(service.contact(EMPLOYER, POSTING, SCHEDULE, 501).phone()).isEqualTo("01099998888");
		}

		@Test
		@DisplayName("미등록 → phone=null·NOT_REGISTERED, 잘못된 형식 → phone=null·INVALID_FORMAT (가상 번호 보완 없음)")
		void missingOrInvalid() {
			stub("PENDING", 1, null);
			var none = service.contact(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(none.phone()).isNull();
			assertThat(none.phoneState()).isEqualTo("NOT_REGISTERED");
			stub("SELECTED", 1, "+82-10-1234");
			var bad = service.contact(EMPLOYER, POSTING, SCHEDULE, 501);
			assertThat(bad.phone()).isNull();
			assertThat(bad.phoneState()).isEqualTo("INVALID_FORMAT");
		}
	}

	@Test
	@DisplayName("응답 레코드에 전체 번호는 Contact에만, 주민번호·식별정보·HMAC·계좌·비밀번호 필드는 어느 응답에도 없음")
	void responseFields() {
		for (Class<?> type : List.of(WorkApplicantResponses.Page.class, WorkApplicantResponses.Item.class, WorkApplicantResponses.Detail.class,
				WorkApplicantResponses.Contact.class)) {
			var names = Arrays.stream(type.getRecordComponents()).map(c -> c.getName().toLowerCase()).toList();
			assertThat(names).as(type.getSimpleName()).noneMatch(n -> n.contains("resident") || n.contains("identity")
				|| n.contains("hmac") || n.contains("account") || n.contains("password") || n.contains("bank") || n.contains("cipher"));
			if (type != WorkApplicantResponses.Contact.class) assertThat(names).as(type.getSimpleName()).doesNotContain("phone");
		}
	}
}

package com.wonday.farm.application.service;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.application.testsupport.ApplicationTestData.memberWithRoleCode;
import static com.wonday.farm.application.testsupport.MyApplicationRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import com.wonday.farm.application.exception.MyApplicationQueryException;
import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import com.wonday.farm.application.repository.MyApplicationQueryRepository.Row;
import com.wonday.farm.application.validation.ApplicationHistoryCursor;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 실제 서비스 + 실제 매퍼·코드 목록, 저장소는 mock. SQL 실행·스냅샷 격리는 확인하지 않는다.
@DisplayName("APP-04 조회 서비스 (저장소 mock)")
class MyApplicationQueryServiceTest {
	private static final long WORKER = 5L;
	private final MemberRepository members = mock(MemberRepository.class);
	private final MyApplicationQueryRepository repository = mock(MyApplicationQueryRepository.class);
	private final MyApplicationQueryService service = new MyApplicationQueryService(members, repository,
		new ApplicationConditionMapper(new AgriculturalCodeCatalog()));

	@BeforeEach
	void worker() {
		given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.WORKER)));
	}

	private static Row app(long id) {
		return row(v -> { v.put("applicationId", id); v.put("sortAppliedAt", LocalDateTime.parse("2026-12-01T00:00").plusMinutes(id)); });
	}
	private static List<Row> apps(long... ids) { return LongStream.of(ids).mapToObj(MyApplicationQueryServiceTest::app).toList(); }
	private static Row past(long id, long schedule, int round) {
		return row(v -> { v.put("applicationId", id); v.put("workScheduleId", schedule); v.put("applicationRound", round); v.put("currentFlag", 0); });
	}

	private static void code(Runnable call, String code) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(MyApplicationQueryException.class, e -> assertThat(e.code()).isEqualTo(code));
	}

	@Test
	@DisplayName("트랜잭션 선언: 클래스 단위 readOnly REPEATABLE_READ (선언만 확인)")
	void transactional() {
		var tx = MyApplicationQueryService.class.getAnnotation(Transactional.class);
		assertThat(tx.readOnly()).isTrue();
		assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
	}

	@Nested
	@DisplayName("현재 DB 역할 확인")
	class Role {
		@ParameterizedTest
		@ValueSource(strings = {"EMPLOYER", "ADMIN", ""})
		@DisplayName("DB 역할이 WORKER가 아니면(고용주·모르는 코드) 목록·상세·이력 모두 AccessDenied, 저장소 조회 없음")
		void notWorker(String role) {
			given(members.findById(WORKER)).willReturn(Optional.of(role.equals("EMPLOYER")
				? member(WORKER, MemberRole.EMPLOYER) : memberWithRoleCode(WORKER, role)));
			assertThatThrownBy(() -> service.list(WORKER, null, null, null)).isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.detail(WORKER, 501)).isInstanceOf(AccessDeniedException.class);
			assertThatThrownBy(() -> service.history(WORKER, 501, null, null)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("회원이 없으면 AccessDenied")
		void missing() {
			given(members.findById(WORKER)).willReturn(Optional.empty());
			assertThatThrownBy(() -> service.list(WORKER, null, null, null)).isInstanceOf(AccessDeniedException.class);
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("역할 확인은 잘못된 필터 검사보다 먼저다(고용주에게 400 대신 403)")
		void roleBeforeValidation() {
			given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.EMPLOYER)));
			assertThatThrownBy(() -> service.list(WORKER, "BAD", "0", "%%")).isInstanceOf(AccessDeniedException.class);
		}
	}

	@Nested
	@DisplayName("목록 입력값")
	class ListInput {
		@Test
		@DisplayName("필터 생략 → ALL, limit 생략 → 20(조회는 21건), 커서 없음 → 경계 null")
		void defaults() {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			var page = service.list(WORKER, null, null, null);
			verify(repository).findCurrent(WORKER, "ALL", null, null, 21);
			assertThat(page.items()).isEmpty();
			assertThat(page.nextCursor()).isNull();
			assertThat(page.hasMore()).isFalse();
		}

		@ParameterizedTest
		@ValueSource(strings = {"ALL", "PENDING", "CONFIRMED", "NOT_SELECTED", "CANCELLED", "EARLY_ENDED", "COMPLETED"})
		@DisplayName("허용 필터 7종은 그대로 SQL 필터로 전달")
		void filters(String filter) {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			service.list(WORKER, filter, null, null);
			verify(repository).findCurrent(WORKER, filter, null, null, 21);
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "pending", "UNKNOWN", "ASSIGNMENT_CANCELLED", "ALL,PENDING", "PENDING "})
		@DisplayName("빈 값·소문자·UNKNOWN·조합·공백 포함 필터는 INVALID_REQUEST, 조회 없음")
		void badFilter(String filter) {
			code(() -> service.list(WORKER, filter, null, null), "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@ParameterizedTest
		@ValueSource(strings = {"1", "100"})
		@DisplayName("limit 경계 1·100 허용(조회는 limit+1건)")
		void limitBounds(String limit) {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			service.list(WORKER, null, limit, null);
			verify(repository).findCurrent(WORKER, "ALL", null, null, Integer.parseInt(limit) + 1);
		}

		@ParameterizedTest
		@ValueSource(strings = {"0", "101", "", " ", "-1", "+5", "1.5", "abc", "1000", "99999999999", "1e2"})
		@DisplayName("limit 0·101·빈 값·부호·소수·문자·범위 초과는 INVALID_REQUEST")
		void badLimit(String limit) {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			code(() -> service.list(WORKER, null, limit, null), "INVALID_REQUEST");
			code(() -> service.history(WORKER, 501, limit, null), "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("목록 페이지·커서")
	class ListPaging {
		@Test
		@DisplayName("size+1건이 오면 size건만 표시, hasMore=true, 다음 커서는 마지막 표시 항목 ID")
		void hasMore() {
			given(repository.findCurrent(WORKER, "ALL", null, null, 3)).willReturn(apps(30, 20, 10));
			var page = service.list(WORKER, null, "2", null);
			assertThat(page.items()).extracting(item -> item.applicationId()).containsExactly(30L, 20L);
			assertThat(page.hasMore()).isTrue();
			assertThat(page.nextCursor()).isEqualTo(ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 20));
		}

		@Test
		@DisplayName("정확히 size건이면 마지막 페이지: nextCursor=null, hasMore=false")
		void exact() {
			given(repository.findCurrent(WORKER, "ALL", null, null, 3)).willReturn(apps(30, 20));
			var page = service.list(WORKER, null, "2", null);
			assertThat(page.items()).hasSize(2);
			assertThat(page.hasMore()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		@Test
		@DisplayName("다음 페이지: 커서의 앵커를 본인 조건(findOwned)으로 다시 읽고 그 정렬 시각·ID를 경계로 전달")
		void next() {
			var anchor = app(20);
			given(repository.findOwned(WORKER, 20)).willReturn(Optional.of(anchor));
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(apps(10));
			var page = service.list(WORKER, "PENDING", "2", ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "PENDING", 20));
			verify(repository).findCurrent(WORKER, "PENDING", anchor.getSortAppliedAt(), 20L, 3);
			assertThat(page.hasMore()).isFalse();
			assertThat(page.nextCursor()).isNull();
		}

		@Test
		@DisplayName("목록 앵커가 재지원으로 과거 회차(currentFlag 0)가 돼도 거절하지 않고 원래 경계 시각을 유지")
		void anchorBecamePast() {
			var anchor = past(20, 201, 1);
			given(repository.findOwned(WORKER, 20)).willReturn(Optional.of(anchor));
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			service.list(WORKER, null, null, ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 20));
			verify(repository).findCurrent(WORKER, "ALL", anchor.getSortAppliedAt(), 20L, 21);
		}

		@Test
		@DisplayName("없는 앵커·타인의 앵커(findOwned 빈 결과)는 INVALID_CURSOR, 목록 조회 없음")
		void missingAnchor() {
			given(repository.findOwned(WORKER, 999)).willReturn(Optional.empty());
			code(() -> service.list(WORKER, null, null, ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 999)), "INVALID_CURSOR");
			verify(repository, never()).findCurrent(anyLong(), anyString(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("다른 계정·다른 필터·HISTORY 커서로 목록을 요청하면 INVALID_CURSOR, 앵커 조회 없음")
		void foreignCursor() {
			code(() -> service.list(WORKER, null, null, ApplicationHistoryCursor.encode(6, "LIST", 0, "ALL", 20)), "INVALID_CURSOR");
			code(() -> service.list(WORKER, "PENDING", null, ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 20)), "INVALID_CURSOR");
			code(() -> service.list(WORKER, null, null, ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 20)), "INVALID_CURSOR");
			verify(repository, never()).findOwned(anyLong(), anyLong());
		}

		@Test
		@DisplayName("커서와 별도로 저장소 조회는 항상 principal의 workerId로 한다")
		void ownerAlwaysPrincipal() {
			given(repository.findOwned(WORKER, 20)).willReturn(Optional.of(app(20)));
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			service.list(WORKER, null, null, ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 20));
			verify(repository).findOwned(eq(WORKER), eq(20L));
			verify(repository).findCurrent(eq(WORKER), eq("ALL"), any(), eq(20L), eq(21));
		}
	}

	@Nested
	@DisplayName("상세")
	class Detail {
		@Test
		@DisplayName("본인 지원은 매퍼 결과를 반환(같은 버전 → UNCHANGED)")
		void owned() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			var detail = service.detail(WORKER, 501);
			assertThat(detail.applicationId()).isEqualTo(501);
			assertThat(detail.conditionComparisonStatus()).isEqualTo("UNCHANGED");
		}

		@Test
		@DisplayName("없는 지원과 타인의 지원은 같은 404 APPLICATION_NOT_FOUND(저장소는 본인 조건으로만 조회)")
		void notFound() {
			given(repository.findOwned(WORKER, 777)).willReturn(Optional.empty());
			code(() -> service.detail(WORKER, 777), "APPLICATION_NOT_FOUND");
			verify(repository).findOwned(WORKER, 777);
		}

		@ParameterizedTest
		@ValueSource(longs = {0, -1, Long.MIN_VALUE})
		@DisplayName("서비스 직접 호출에서도 0·음수 ID는 INVALID_REQUEST")
		void nonPositive(long id) {
			code(() -> service.detail(WORKER, id), "INVALID_REQUEST");
			code(() -> service.history(WORKER, id, null, null), "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("과거 회차")
	class History {
		@Test
		@DisplayName("기준 지원 소유권 먼저 확인: 없거나 타인이면 404, 과거 조회·커서 해석 없음")
		void ownershipFirst() {
			given(repository.findOwned(WORKER, 777)).willReturn(Optional.empty());
			code(() -> service.history(WORKER, 777, "0", "%%"), "APPLICATION_NOT_FOUND");
			verify(repository, never()).findPast(anyLong(), anyLong(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("기준 지원의 일정으로 과거 회차만 조회(기본 20 → 21건), 현재 회차 ID를 함께 반환")
		void firstPage() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findPast(WORKER, 201, null, null, 21)).willReturn(List.of(past(401, 201, 2), past(301, 201, 1)));
			given(repository.findCurrentId(WORKER, 201)).willReturn(Optional.of(501L));
			var history = service.history(WORKER, 501, null, null);
			assertThat(history.currentApplicationId()).isEqualTo(501L);
			assertThat(history.items()).extracting(item -> item.applicationId()).containsExactly(401L, 301L);
			assertThat(history.items()).allSatisfy(item -> assertThat(item.current()).isFalse());
			assertThat(history.hasMore()).isFalse();
			assertThat(history.nextCursor()).isNull();
		}

		@Test
		@DisplayName("과거 회차 ID를 기준으로 호출해도 같은 일정 이력, 현재 회차가 없으면 currentApplicationId=null")
		void fromPast() {
			given(repository.findOwned(WORKER, 301)).willReturn(Optional.of(past(301, 201, 1)));
			given(repository.findPast(WORKER, 201, null, null, 21)).willReturn(List.of(past(301, 201, 1)));
			given(repository.findCurrentId(WORKER, 201)).willReturn(Optional.empty());
			var history = service.history(WORKER, 301, null, null);
			assertThat(history.currentApplicationId()).isNull();
			assertThat(history.items()).hasSize(1);
		}

		@Test
		@DisplayName("size+1건 → hasMore, 다음 커서는 HISTORY·일정 201·마지막 표시 항목 ID")
		void more() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findPast(WORKER, 201, null, null, 2)).willReturn(List.of(past(401, 201, 3), past(301, 201, 2)));
			given(repository.findCurrentId(WORKER, 201)).willReturn(Optional.of(501L));
			var history = service.history(WORKER, 501, "1", null);
			assertThat(history.items()).extracting(item -> item.applicationId()).containsExactly(401L);
			assertThat(history.hasMore()).isTrue();
			assertThat(history.nextCursor()).isEqualTo(ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 401));
		}

		@Test
		@DisplayName("다음 페이지: 앵커의 회차·ID를 경계로 전달")
		void nextPage() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findOwned(WORKER, 401)).willReturn(Optional.of(past(401, 201, 3)));
			given(repository.findPast(anyLong(), anyLong(), any(), any(), anyInt())).willReturn(List.of());
			service.history(WORKER, 501, "1", ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 401));
			verify(repository).findPast(WORKER, 201, 3, 401L, 2);
		}

		@Test
		@DisplayName("앵커가 다른 일정이거나 현재 회차면 INVALID_CURSOR")
		void anchorScope() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findOwned(WORKER, 402)).willReturn(Optional.of(past(402, 202, 1)));
			given(repository.findOwned(WORKER, 502)).willReturn(Optional.of(row(v -> v.put("applicationId", 502L))));
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 402)), "INVALID_CURSOR");
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 502)), "INVALID_CURSOR");
			verify(repository, never()).findPast(anyLong(), anyLong(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("없는/타인 앵커, 다른 일정·LIST·다른 계정 커서는 INVALID_CURSOR")
		void badCursor() {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findOwned(WORKER, 999)).willReturn(Optional.empty());
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(WORKER, "HISTORY", 201, "ALL", 999)), "INVALID_CURSOR");
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(WORKER, "HISTORY", 202, "ALL", 401)), "INVALID_CURSOR");
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 401)), "INVALID_CURSOR");
			code(() -> service.history(WORKER, 501, null, ApplicationHistoryCursor.encode(6, "HISTORY", 201, "ALL", 401)), "INVALID_CURSOR");
		}

		@Test
		@DisplayName("목록 커서와 과거 이력은 별도: 목록 조회는 findPast를, 이력 조회는 findCurrent를 부르지 않는다")
		void separated() {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
			service.list(WORKER, null, null, null);
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findPast(anyLong(), anyLong(), any(), any(), anyInt())).willReturn(new ArrayList<>());
			service.history(WORKER, 501, null, null);
			verify(repository).findCurrent(anyLong(), anyString(), isNull(), isNull(), anyInt());
			verify(repository).findPast(anyLong(), anyLong(), isNull(), isNull(), anyInt());
		}
	}
}

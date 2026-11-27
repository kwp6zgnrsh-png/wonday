package com.wonday.farm.application.api;

import static com.wonday.farm.application.testsupport.ApplicationTestData.member;
import static com.wonday.farm.application.testsupport.ApplicationTestData.memberWithRoleCode;
import static com.wonday.farm.application.testsupport.MyApplicationRows.assignment;
import static com.wonday.farm.application.testsupport.MyApplicationRows.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.application.exception.MyApplicationQueryErrorHandler;
import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import com.wonday.farm.application.service.ApplicationConditionMapper;
import com.wonday.farm.application.service.MyApplicationQueryService;
import com.wonday.farm.application.validation.ApplicationHistoryCursor;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 컨트롤러 + 실제 서비스·매퍼 + 실제 오류 처리기, 저장소·회원 저장소는 mock.
// Security 필터 체인(비로그인 401, hasRole, 세션), Spring Boot JsonMapper 설정, SQL 실행은 이 테스트에 없다.
@DisplayName("APP-04 내 지원 API (standalone MockMvc)")
class MyApplicationApiTest {
	private static final long WORKER = 5L;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t";
	private final MemberRepository members = mock(MemberRepository.class);
	private final MyApplicationQueryRepository repository = mock(MyApplicationQueryRepository.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MyApplicationApi(new MyApplicationQueryService(members, repository,
			new ApplicationConditionMapper(new AgriculturalCodeCatalog()))))
		.setControllerAdvice(new MyApplicationQueryErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		signInAs(WORKER);
		given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.WORKER)));
		given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of());
	}

	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private static void signInAs(long id) {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(id);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	private MockHttpServletResponse call(RequestBuilder request) throws Exception {
		var response = mvc.perform(request).andReturn().getResponse();
		response.setCharacterEncoding("UTF-8");
		return response;
	}
	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
	private void expectError(RequestBuilder request, int status, String code) throws Exception {
		var response = call(request);
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = body(response);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.get("message").asString()).isNotBlank();
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("s3cr3t", "jdbc:", "Exception");
	}

	@Nested
	@DisplayName("성공 응답 형식")
	class Success {
		@Test
		@DisplayName("빈 목록: 200, no-store, items=[]·nextCursor=null·hasMore=false, 기본 ALL·20")
		void empty() throws Exception {
			var response = call(get("/api/me/applications"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentType()).startsWith("application/json");
			var json = body(response);
			assertThat(json.get("items").isArray()).isTrue();
			assertThat(json.get("items").size()).isZero();
			assertThat(json.get("nextCursor").isNull()).isTrue();
			assertThat(json.get("hasMore").asBoolean()).isFalse();
			verify(repository).findCurrent(WORKER, "ALL", null, null, 21);
		}

		@Test
		@DisplayName("목록 항목 JSON: +09:00·마이크로초, 날짜 YYYY-MM-DD, null 필드 유지, 다음 커서 문자열")
		void itemJson() throws Exception {
			given(repository.findCurrent(WORKER, "PENDING", null, null, 2)).willReturn(List.of(row(), row(v -> v.put("applicationId", 400L))));
			var json = body(call(get("/api/me/applications").param("status", "PENDING").param("limit", "1")));
			var item = json.get("items").get(0);
			assertThat(item.get("appliedAt").asString()).isEqualTo("2026-12-01T15:00:00.123456+09:00");
			assertThat(item.get("workDate").asString()).isEqualTo("2026-12-10");
			assertThat(item.get("legacyAppliedAt").isNull()).isTrue();
			assertThat(item.get("completedAt").isNull()).isTrue();
			assertThat(item.get("assignmentId").isNull()).isTrue();
			assertThat(item.get("cancellationType").isNull()).isTrue();
			assertThat(item.get("appliedTimeBasis").asString()).isEqualTo("UTC");
			assertThat(item.get("current").asBoolean()).isTrue();
			assertThat(item.get("conditionComparisonStatus").asString()).isEqualTo("UNCHANGED");
			assertThat(json.get("hasMore").asBoolean()).isTrue();
			assertThat(json.get("nextCursor").asString()).isEqualTo(ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "PENDING", 501));
		}

		@Test
		@DisplayName("기존 UTC 누락 행 JSON: appliedAt=null, legacyAppliedAt 원본 문자열, LEGACY_LOCAL_UNKNOWN_ZONE")
		void legacyJson() throws Exception {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt())).willReturn(List.of(row(v -> {
				v.put("appliedAtUtc", null); v.put("legacyAppliedAt", LocalDateTime.parse("2025-05-01T08:30:00.000001")); })));
			var item = body(call(get("/api/me/applications"))).get("items").get(0);
			assertThat(item.get("appliedAt").isNull()).isTrue();
			assertThat(item.get("legacyAppliedAt").asString()).isEqualTo("2025-05-01T08:30:00.000001");
			assertThat(item.get("appliedTimeBasis").asString()).isEqualTo("LEGACY_LOCAL_UNKNOWN_ZONE");
		}

		@Test
		@DisplayName("상세 JSON: 조건 두 묶음·비교 결과·약정 오프셋 시각, 200 no-store")
		void detailJson() throws Exception {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row(v -> {
				assignment(v); v.put("completedAtUtc", LocalDateTime.parse("2026-12-10T08:00:00.000009")); v.put("displayStatus", "COMPLETED"); })));
			var response = call(get("/api/me/applications/501"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var json = body(response);
			assertThat(json.get("appliedConditions").get("availability").asString()).isEqualTo("AVAILABLE");
			assertThat(json.get("latestConditions").get("plannedStartAt").asString()).isEqualTo("2026-12-10T09:00:00+09:00");
			assertThat(json.get("latestConditions").get("latitude").decimalValue()).isEqualByComparingTo("37");
			assertThat(json.get("changedFields").size()).isZero();
			assertThat(json.get("comparisonComplete").asBoolean()).isTrue();
			var assignmentJson = json.get("assignment");
			assertThat(assignmentJson.get("completedAt").asString()).isEqualTo("2026-12-10T17:00:00.000009+09:00");
			assertThat(assignmentJson.get("confirmedAt").asString()).isEqualTo("2026-12-02T10:00:00.000001+09:00");
			assertThat(assignmentJson.get("agreedConditions").get("plannedStartAt").asString()).isEqualTo("2026-12-10T08:00:00+09:00");
			assertThat(assignmentJson.get("change").isNull()).isTrue();
		}

		@Test
		@DisplayName("과거 회차 JSON: currentApplicationId·items·nextCursor·hasMore")
		void historyJson() throws Exception {
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.of(row()));
			given(repository.findPast(WORKER, 201, null, null, 21)).willReturn(List.of(row(v -> { v.put("applicationId", 301L); v.put("currentFlag", 0); })));
			given(repository.findCurrentId(WORKER, 201)).willReturn(Optional.of(501L));
			var response = call(get("/api/me/applications/501/history"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			var json = body(response);
			assertThat(json.get("currentApplicationId").asLong()).isEqualTo(501);
			assertThat(json.get("items").get(0).get("current").asBoolean()).isFalse();
			assertThat(json.get("nextCursor").isNull()).isTrue();
		}
	}

	@Nested
	@DisplayName("본인 확인")
	class Owner {
		@Test
		@DisplayName("principal의 회원 ID만 사용: workerId·memberId 쿼리 파라미터는 무시")
		void principalOnly() throws Exception {
			call(get("/api/me/applications").param("workerId", "6").param("memberId", "6"));
			verify(repository).findCurrent(WORKER, "ALL", null, null, 21);
			verify(repository, never()).findCurrent(org.mockito.ArgumentMatchers.eq(6L), anyString(), any(), any(), anyInt());
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.empty());
			call(get("/api/me/applications/501").param("workerId", "6"));
			verify(repository).findOwned(WORKER, 501);
		}

		@Test
		@DisplayName("다른 세션 principal(6)이면 그 회원으로만 조회")
		void otherPrincipal() throws Exception {
			signInAs(6);
			given(members.findById(6L)).willReturn(Optional.of(member(6, MemberRole.WORKER)));
			call(get("/api/me/applications"));
			verify(repository).findCurrent(6L, "ALL", null, null, 21);
		}

		@Test
		@DisplayName("없는 지원과 타인의 지원은 같은 404 APPLICATION_NOT_FOUND(본문 동일)")
		void sameNotFound() throws Exception {
			given(repository.findOwned(WORKER, 777)).willReturn(Optional.empty());
			given(repository.findOwned(WORKER, 888)).willReturn(Optional.empty());
			var a = call(get("/api/me/applications/777"));
			var b = call(get("/api/me/applications/888/history"));
			expectError(get("/api/me/applications/777"), 404, "APPLICATION_NOT_FOUND");
			assertThat(a.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(b.getContentAsString(StandardCharsets.UTF_8));
		}

		@Test
		@DisplayName("고용주·DB 역할 변경(모르는 코드)·회원 삭제는 403 WORKER_ROLE_REQUIRED, 저장소 조회 없음")
		void forbidden() throws Exception {
			given(members.findById(WORKER)).willReturn(Optional.of(member(WORKER, MemberRole.EMPLOYER)));
			expectError(get("/api/me/applications"), 403, "WORKER_ROLE_REQUIRED");
			given(members.findById(WORKER)).willReturn(Optional.of(memberWithRoleCode(WORKER, "SUSPENDED")));
			expectError(get("/api/me/applications/501"), 403, "WORKER_ROLE_REQUIRED");
			given(members.findById(WORKER)).willReturn(Optional.empty());
			expectError(get("/api/me/applications/501/history"), 403, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("principal 없음(standalone에서 직접 호출) → 컨트롤러가 403. 실제 비로그인 401은 Security 체인 범위라 확인하지 않음")
		void noPrincipal() throws Exception {
			SecurityContextHolder.clearContext();
			expectError(get("/api/me/applications"), 403, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(members, repository);
		}
	}

	@Nested
	@DisplayName("입력 검증")
	class Input {
		@ParameterizedTest
		@ValueSource(strings = {"0", "-1", "01", "1.5", "abc", "1e3", "9223372036854775808", "99999999999999999999", "+1", " 1"})
		@DisplayName("지원 ID: 0·음수·앞자리 0·소수·문자·지수·Long 범위 초과·부호·공백은 400 INVALID_REQUEST, 조회 없음")
		void badId(String id) throws Exception {
			expectError(get("/api/me/applications/{id}", id), 400, "INVALID_REQUEST");
			expectError(get("/api/me/applications/{id}/history", id), 400, "INVALID_REQUEST");
			verify(repository, never()).findOwned(anyLong(), anyLong());
		}

		@Test
		@DisplayName("Long 최댓값 ID는 형식상 허용(없으면 404)")
		void maxId() throws Exception {
			given(repository.findOwned(WORKER, Long.MAX_VALUE)).willReturn(Optional.empty());
			expectError(get("/api/me/applications/9223372036854775807"), 404, "APPLICATION_NOT_FOUND");
		}

		@ParameterizedTest
		@ValueSource(strings = {"", "pending", "UNKNOWN", "DONE"})
		@DisplayName("빈 값·잘못된 필터는 400 INVALID_REQUEST")
		void badFilter(String status) throws Exception {
			expectError(get("/api/me/applications").param("status", status), 400, "INVALID_REQUEST");
		}

		@ParameterizedTest
		@ValueSource(strings = {"1", "100"})
		@DisplayName("limit 1·100은 200")
		void limitOk(String limit) throws Exception {
			assertThat(call(get("/api/me/applications").param("limit", limit)).getStatus()).isEqualTo(200);
		}

		@ParameterizedTest
		@ValueSource(strings = {"0", "101", "", "-5", "2.0", "x"})
		@DisplayName("limit 0·101·빈 값·음수·소수·문자는 400 INVALID_REQUEST")
		void badLimit(String limit) throws Exception {
			expectError(get("/api/me/applications").param("limit", limit), 400, "INVALID_REQUEST");
		}

		@ParameterizedTest
		@ValueSource(strings = {"status", "limit", "cursor"})
		@DisplayName("알려진 파라미터 중복(같은 값이어도)은 400 INVALID_REQUEST, 조회 없음")
		void duplicate(String name) throws Exception {
			String value = name.equals("status") ? "ALL" : name.equals("limit") ? "20"
				: ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 501);
			expectError(get("/api/me/applications").param(name, value, value), 400, "INVALID_REQUEST");
			verify(repository, never()).findCurrent(anyLong(), anyString(), any(), any(), anyInt());
		}

		@Test
		@DisplayName("과거 회차의 limit·cursor 중복도 400")
		void duplicateHistory() throws Exception {
			expectError(get("/api/me/applications/501/history").param("limit", "1", "2"), 400, "INVALID_REQUEST");
			expectError(get("/api/me/applications/501/history").param("cursor", "a", "b"), 400, "INVALID_REQUEST");
		}

		@Test
		@DisplayName("잘못된 커서는 400 INVALID_CURSOR(INVALID_REQUEST와 구분)")
		void badCursor() throws Exception {
			expectError(get("/api/me/applications").param("cursor", "not a cursor"), 400, "INVALID_CURSOR");
			expectError(get("/api/me/applications").param("cursor", ApplicationHistoryCursor.encode(6, "LIST", 0, "ALL", 501)), 400, "INVALID_CURSOR");
			given(repository.findOwned(WORKER, 501)).willReturn(Optional.empty());
			expectError(get("/api/me/applications").param("cursor", ApplicationHistoryCursor.encode(WORKER, "LIST", 0, "ALL", 501)), 400, "INVALID_CURSOR");
		}

		@Test
		@DisplayName("빈 cursor 값은 INVALID_CURSOR(생략과 다르게 취급)")
		void emptyCursor() throws Exception {
			expectError(get("/api/me/applications").param("cursor", ""), 400, "INVALID_CURSOR");
		}
	}

	@Nested
	@DisplayName("서버 오류")
	class Failures {
		@Test
		@DisplayName("DB 조회 불가(DataAccess·트랜잭션 생성 실패·트랜잭션 시간 초과)는 503 SERVICE_UNAVAILABLE, 내부 정보 미노출")
		void unavailable() throws Exception {
			given(repository.findCurrent(anyLong(), anyString(), any(), any(), anyInt()))
				.willThrow(new DataAccessResourceFailureException(SECRET), new QueryTimeoutException(SECRET),
					new CannotCreateTransactionException(SECRET), new TransactionTimedOutException(SECRET));
			for (int i = 0; i < 4; i++) expectError(get("/api/me/applications"), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("그 밖의 예외는 500 INTERNAL_SERVER_ERROR, 내부 메시지 미노출")
		void unexpected() throws Exception {
			given(repository.findOwned(WORKER, 501)).willThrow(new IllegalStateException(SECRET));
			expectError(get("/api/me/applications/501"), 500, "INTERNAL_SERVER_ERROR");
		}

		@Test
		@DisplayName("DB 역할 조회 자체의 DB 장애도 503")
		void memberLookupFailure() throws Exception {
			given(members.findById(WORKER)).willThrow(new DataAccessResourceFailureException(SECRET));
			expectError(get("/api/me/applications"), 503, "SERVICE_UNAVAILABLE");
		}
	}
}

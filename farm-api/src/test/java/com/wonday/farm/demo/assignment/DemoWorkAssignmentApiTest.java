package com.wonday.farm.demo.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.service.WorkAssignmentQueryService;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoWorkAssignmentApi + DemoWorkAssignmentErrorHandler. MemberRepository·DemoWorkAssignmentRepository·
// WorkAssignmentQueryService는 mock. principal은 SecurityContext에 직접 넣는다.
// 실제 프로필별 빈 등록, SecurityConfig의 hasRole("WORKER") 경로 제한·세션·CSRF, 목록 SQL(workerId 필터·id desc·LIMIT 100·
// projection 변환)은 검증하지 않는다.
@DisplayName("farm-demo 약정 확인 API (/demo/api/work-assignments)")
class DemoWorkAssignmentApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long WORKER_ID = 5L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final DemoWorkAssignmentRepository repository = mock(DemoWorkAssignmentRepository.class);
	private final WorkAssignmentQueryService assignments = mock(WorkAssignmentQueryService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoWorkAssignmentApi(members, repository, assignments))
		.setControllerAdvice(new DemoWorkAssignmentErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
		.build();

	@BeforeEach
	void signedInWorker() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(WORKER_ID);
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, "WORKER")));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("목록")
	class ListOptions {
		@Test
		@DisplayName("세션 근로자 ID로만 조회하고 쿼리의 workerId·memberId는 무시한다. 필드와 coordinatesMissing(양쪽 null일 때만 true)을 반환")
		void listUsesPrincipal() throws Exception {
			given(repository.findMine(WORKER_ID)).willReturn(List.of(
				new Row(41, LocalDate.parse("2026-11-30"), "약정 작업지 A", null, null),
				new Row(40, LocalDate.parse("2026-11-29"), "약정 작업지 B", new BigDecimal("37.123457"), null),
				new Row(39, null, "약정 작업지 C", null, new BigDecimal("127.987654")),
				new Row(38, LocalDate.parse("2026-11-27"), "약정 작업지 D", new BigDecimal("0.000000"), new BigDecimal("0.000000"))));

			var response = perform(get("/demo/api/work-assignments").param("workerId", "99").param("memberId", "99"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			verify(repository).findMine(WORKER_ID);
			verifyNoMoreInteractions(repository);
			verifyNoInteractions(assignments);
			JsonNode rows = body(response).get("assignments");
			assertThat(rows).hasSize(4);
			assertThat(rows.get(0).get("assignmentId").asLong()).isEqualTo(41L);
			assertThat(rows.get(0).get("agreedWorkDate").asString()).isEqualTo("2026-11-30");
			assertThat(rows.get(0).get("agreedWorkLocation").asString()).isEqualTo("약정 작업지 A");
			assertThat(rows.get(0).get("coordinatesMissing").asBoolean()).isTrue();
			assertThat(rows.get(1).get("coordinatesMissing").asBoolean()).isFalse();
			assertThat(rows.get(2).get("coordinatesMissing").asBoolean()).isFalse();
			assertThat(rows.get(2).get("agreedWorkDate").isNull()).isTrue();
			assertThat(rows.get(3).get("coordinatesMissing").asBoolean()).isFalse();
		}

		@Test
		@DisplayName("본인 약정이 없으면 빈 목록 200 — 가짜 약정을 만들지 않는다")
		void emptyList() throws Exception {
			given(repository.findMine(WORKER_ID)).willReturn(List.of());

			var response = perform(get("/demo/api/work-assignments"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(body(response).get("assignments").isArray()).isTrue();
			assertThat(body(response).get("assignments")).isEmpty();
			assertNoStore(response);
		}

		@Test
		@DisplayName("요청마다 현재 DB 역할을 다시 확인한다 (두 번째 요청 전 EMPLOYER로 바뀌면 403)")
		void roleRecheckedEachRequest() throws Exception {
			given(repository.findMine(WORKER_ID)).willReturn(List.of());
			assertThat(perform(get("/demo/api/work-assignments")).getStatus()).isEqualTo(200);

			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, "EMPLOYER")));
			assertThat(perform(get("/demo/api/work-assignments")).getStatus()).isEqualTo(403);

			verify(members, times(2)).findById(WORKER_ID);
			verify(repository, times(1)).findMine(WORKER_ID);
		}
	}

	@Nested
	@DisplayName("역할 거절 → 403 WORKER_ROLE_REQUIRED, 약정 Repository·조회 서비스 호출 없음")
	class Role {
		@ParameterizedTest(name = "{0}")
		@MethodSource("com.wonday.farm.demo.assignment.DemoWorkAssignmentApiTest#rejectedMembers")
		@DisplayName("회원 없음·EMPLOYER·해석 불가 역할")
		void rejected(String label, Optional<Member> member) throws Exception {
			given(members.findById(WORKER_ID)).willReturn(member);

			for (String path : List.of("/demo/api/work-assignments", "/demo/api/work-assignments/31")) {
				var response = perform(get(path));
				assertThat(response.getStatus()).isEqualTo(403);
				assertThat(body(response).get("code").asString()).isEqualTo("WORKER_ROLE_REQUIRED");
				assertNoStore(response);
			}
			verifyNoInteractions(repository, assignments);
		}
	}

	@Nested
	@DisplayName("상세")
	class Detail {
		@Test
		@DisplayName("같은 WorkAssignmentQueryService에 세션 근로자 ID와 경로 assignmentId를 전달하고 좌표를 JSON 숫자로 반환한다")
		void numbers() throws Exception {
			given(assignments.find(WORKER_ID, 31L)).willReturn(response(new BigDecimal("37.123457"), new BigDecimal("-127.987654")));

			var response = perform(get("/demo/api/work-assignments/31").param("workerId", "99"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			verify(assignments).find(WORKER_ID, 31L);
			verifyNoMoreInteractions(assignments);
			verifyNoInteractions(repository);
			JsonNode body = body(response);
			assertThat(body.get("agreedLatitude").isNumber()).isTrue();
			assertThat(body.get("agreedLatitude").decimalValue()).isEqualByComparingTo("37.123457");
			assertThat(body.get("agreedLongitude").decimalValue()).isEqualByComparingTo("-127.987654");
			assertThat(body.get("workerId").asLong()).isEqualTo(WORKER_ID);
		}

		@Test
		@DisplayName("두 좌표 null 약정은 두 필드가 명시적 null인 200 응답")
		void nulls() throws Exception {
			given(assignments.find(WORKER_ID, 31L)).willReturn(response(null, null));

			JsonNode body = body(perform(get("/demo/api/work-assignments/31")));

			assertThat(body.has("agreedLatitude")).isTrue();
			assertThat(body.get("agreedLatitude").isNull()).isTrue();
			assertThat(body.has("agreedLongitude")).isTrue();
			assertThat(body.get("agreedLongitude").isNull()).isTrue();
		}

		@Test
		@DisplayName("타인 소유·없는 약정 404, 기존 필수정보 누락 503을 서비스 코드 그대로 변환한다")
		void serviceErrors() throws Exception {
			given(assignments.find(WORKER_ID, 31L)).willThrow(new InsuranceApiException(HttpStatus.NOT_FOUND,
				"WORK_ASSIGNMENT_NOT_FOUND", "해당 근로자의 확정 근무를 찾을 수 없습니다."));
			given(assignments.find(WORKER_ID, 32L)).willThrow(InsuranceApiException.unavailable());

			var notFound = perform(get("/demo/api/work-assignments/31"));
			assertThat(notFound.getStatus()).isEqualTo(404);
			assertThat(body(notFound).get("code").asString()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND");
			assertNoStore(notFound);
			var unavailable = perform(get("/demo/api/work-assignments/32"));
			assertThat(unavailable.getStatus()).isEqualTo(503);
			assertThat(body(unavailable).get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
			assertNoStore(unavailable);
		}

		@ParameterizedTest(name = "assignmentId={0}")
		@ValueSource(strings = {"abc", "1.5", "99999999999999999999", "-"})
		@DisplayName("잘못된 ID 형식은 400 INVALID_REQUEST이고 서비스 호출 없음")
		void invalidId(String id) throws Exception {
			var response = perform(get("/demo/api/work-assignments/" + id));

			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(body(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
			assertNoStore(response);
			verifyNoInteractions(assignments);
		}

		@Test
		@DisplayName("0·음수 ID는 서비스로 전달되어 서비스의 400 INVALID_REQUEST가 그대로 나간다")
		void nonPositiveIdDelegated() throws Exception {
			given(assignments.find(WORKER_ID, 0L)).willThrow(InsuranceApiException.invalidRequest());

			var response = perform(get("/demo/api/work-assignments/0"));

			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(body(response).get("code").asString()).isEqualTo("INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("내부 오류 변환 — 메시지·SQL·비밀값 비노출")
	class Failures {
		@ParameterizedTest(name = "{0}")
		@MethodSource("com.wonday.farm.demo.assignment.DemoWorkAssignmentApiTest#failures")
		@DisplayName("목록·상세 모두 일반 예외는 500, DB 일시 장애는 503")
		void converted(String label, RuntimeException failure, int status, String code) throws Exception {
			given(repository.findMine(WORKER_ID)).willThrow(failure);
			given(assignments.find(anyLong(), anyLong())).willThrow(failure);

			for (String path : List.of("/demo/api/work-assignments", "/demo/api/work-assignments/31")) {
				var response = perform(get(path));
				assertThat(response.getStatus()).isEqualTo(status);
				assertThat(body(response).get("code").asString()).isEqualTo(code);
				assertNoStore(response);
				String raw = response.getContentAsString(StandardCharsets.UTF_8);
				assertThat(raw).doesNotContain("SECRET", "select", "jdbc:", "password", "Exception");
			}
		}
	}

	@Test
	@DisplayName("API·오류 처리기·Repository에 @Profile(\"farm-demo\")이 선언돼 있다 (선언만 확인, 실제 빈 등록은 미검증)")
	void profileDeclared() {
		for (Class<?> type : List.of(DemoWorkAssignmentApi.class, DemoWorkAssignmentErrorHandler.class, DemoWorkAssignmentRepository.class)) {
			assertThat(type.getAnnotation(Profile.class)).as(type.getSimpleName()).isNotNull();
			assertThat(type.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
		}
	}

	static Stream<Arguments> rejectedMembers() {
		return Stream.of(
			Arguments.of("회원 없음", Optional.empty()),
			Arguments.of("EMPLOYER", Optional.of(member(WORKER_ID, "EMPLOYER"))),
			Arguments.of("해석 불가 역할", Optional.of(member(WORKER_ID, "ADMIN"))),
			Arguments.of("소문자 역할", Optional.of(member(WORKER_ID, "worker"))));
	}

	static Stream<Arguments> failures() {
		return Stream.of(
			Arguments.of("RuntimeException", new IllegalStateException("SECRET token=abc password=pw"), 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("BadSqlGrammar", new BadSqlGrammarException("select", "select * from SECRET",
				new java.sql.SQLException("password leaked")), 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("DataAccessResourceFailure", new DataAccessResourceFailureException("jdbc:mysql://SECRET password=pw"), 503, "SERVICE_UNAVAILABLE"),
			Arguments.of("QueryTimeout(Transient)", new QueryTimeoutException("select SECRET timed out"), 503, "SERVICE_UNAVAILABLE"),
			Arguments.of("CannotCreateTransaction", new CannotCreateTransactionException("jdbc:mysql://SECRET"), 503, "SERVICE_UNAVAILABLE"));
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoStore(MockHttpServletResponse response) {
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

	private static Member member(long id, String role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "role", role);
		return member;
	}

	private static WorkAssignmentResponse response(BigDecimal latitude, BigDecimal longitude) {
		return new WorkAssignmentResponse(31L, WORKER_ID, OffsetDateTime.parse("2026-11-20T10:02:03.456789+09:00"),
			LocalDate.parse("2026-11-30"), "GMT+9", "약정 작업지", "AGREED-CROP", "AGREED-WORK",
			OffsetDateTime.parse("2026-11-30T08:00+09:00"), OffsetDateTime.parse("2026-11-30T17:00+09:00"),
			null, null, 120000L, latitude, longitude);
	}

	private record Row(long assignmentId, LocalDate agreedWorkDate, String agreedWorkLocation,
		BigDecimal agreedLatitude, BigDecimal agreedLongitude) implements DemoWorkAssignmentRepository.Option {
		@Override public long getAssignmentId() { return assignmentId; }
		@Override public LocalDate getAgreedWorkDate() { return agreedWorkDate; }
		@Override public String getAgreedWorkLocation() { return agreedWorkLocation; }
		@Override public BigDecimal getAgreedLatitude() { return agreedLatitude; }
		@Override public BigDecimal getAgreedLongitude() { return agreedLongitude; }
	}
}

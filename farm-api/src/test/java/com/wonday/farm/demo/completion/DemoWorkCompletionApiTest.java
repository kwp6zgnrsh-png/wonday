package com.wonday.farm.demo.completion;

import static com.wonday.farm.demo.completion.DemoWorkCompletionQueryServiceTest.EMPLOYER_ID;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoWorkCompletionApi + 실제 DemoWorkCompletionQueryService + 실제 DemoWorkCompletionErrorHandler.
// MemberRepository·DemoWorkCompletionRepository는 mock, principal은 SecurityContext에 직접 넣는다.
// 실제 farm-demo 프로필 빈 등록, SecurityConfig의 hasRole("EMPLOYER")·403 JSON 핸들러·세션, SQL 실행은 검증하지 않는다.
// 프로필·SQL·보안 설정은 아래에서 선언(문자열·어노테이션)만 확인한다.
@DisplayName("farm-demo 고용주 완료 조회 API (standalone MockMvc)")
class DemoWorkCompletionApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t";

	private final MemberRepository members = mock(MemberRepository.class);
	private final DemoWorkCompletionRepository repository = mock(DemoWorkCompletionRepository.class);
	private final MockMvc mvc = MockMvcBuilders
		.standaloneSetup(new DemoWorkCompletionApi(new DemoWorkCompletionQueryService(members, repository)))
		.setControllerAdvice(new DemoWorkCompletionErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signedInEmployer() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(EMPLOYER_ID);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("응답")
	class Responses {
		@Test
		@DisplayName("화면 GET은 200 text/html, no-store, 완료 화면 리소스")
		void page() throws Exception {
			var response = perform(get("/demo/work-completion"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentType()).startsWith("text/html");
			assertNoStore(response);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("고용주의 개인별 근무 완료");
		}

		@Test
		@DisplayName("목록: 쿼리의 employerId는 무시하고 세션 고용주로 조회, assignments 배열 필드, no-store")
		void list() throws Exception {
			given(repository.findForEmployer(EMPLOYER_ID)).willReturn(List.of(
				DemoWorkCompletionQueryServiceTest.Rows.detail(41, 7, "사과 수확", "근로자 가", LocalDate.parse("2026-11-30"), "GMT+9", null, null, null)));

			var response = perform(get("/demo/api/employer/work-assignments").param("employerId", "99"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			verify(repository).findForEmployer(EMPLOYER_ID);
			JsonNode row = parse(response).get("assignments").get(0);
			assertThat(row.propertyNames()).containsExactlyInAnyOrder("assignmentId", "jobPostingId", "title", "workerName", "agreedWorkDate");
			assertThat(row.get("agreedWorkDate").asString()).isEqualTo("2026-11-30");
		}

		@Test
		@DisplayName("빈 목록은 200과 assignments: []")
		void emptyList() throws Exception {
			given(repository.findForEmployer(EMPLOYER_ID)).willReturn(List.of());

			var body = parse(perform(get("/demo/api/employer/work-assignments")));

			assertThat(body.get("assignments").isArray()).isTrue();
			assertThat(body.get("assignments")).isEmpty();
		}

		@Test
		@DisplayName("미완료 상세: completedAt 키가 JSON null, agreedEndAt은 +09:00, completedBy 없음")
		void detailNotCompleted() throws Exception {
			detailRow(null, null);

			var response = perform(get("/demo/api/employer/work-assignments/41"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			JsonNode body = parse(response);
			assertThat(body.has("completedAt")).isTrue();
			assertThat(body.get("completedAt").isNull()).isTrue();
			assertThat(body.get("agreedEndAt").asString()).isEqualTo("2026-11-30T17:00:00+09:00");
			assertThat(body.get("changeType").isNull()).isTrue();
			assertThat(body.has("completedBy")).isFalse();
			verify(repository).findDetailForEmployer(EMPLOYER_ID, 41);
		}

		@Test
		@DisplayName("완료 상세: completedAt \"…17:00:00.000001+09:00\", 취소 종류 문자열 그대로")
		void detailCompleted() throws Exception {
			detailRow(LocalDateTime.parse("2026-11-30T08:00:00.000001"), "CANCELLED");

			JsonNode body = parse(perform(get("/demo/api/employer/work-assignments/41")));

			assertThat(body.get("completedAt").asString()).isEqualTo("2026-11-30T17:00:00.000001+09:00");
			assertThat(body.get("changeType").asString()).isEqualTo("CANCELLED");
		}
	}

	@Nested
	@DisplayName("오류")
	class Errors {
		@Test
		@DisplayName("근로자 세션은 목록·상세 모두 403 EMPLOYER_ROLE_REQUIRED이고 저장소 조회 없음")
		void worker() throws Exception {
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.WORKER)));

			assertError(perform(get("/demo/api/employer/work-assignments")), 403, "EMPLOYER_ROLE_REQUIRED");
			assertError(perform(get("/demo/api/employer/work-assignments/41")), 403, "EMPLOYER_ROLE_REQUIRED");
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("principal 없음(standalone)은 403이고 회원 조회 없음 — 실제 체인의 익명 401과는 별개")
		void noPrincipal() throws Exception {
			SecurityContextHolder.clearContext();

			assertError(perform(get("/demo/api/employer/work-assignments")), 403, "EMPLOYER_ROLE_REQUIRED");
			verifyNoInteractions(members, repository);
		}

		@Test
		@DisplayName("없는·타인 근무 상세는 404 WORK_ASSIGNMENT_NOT_FOUND")
		void notFound() throws Exception {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.empty());

			assertError(perform(get("/demo/api/employer/work-assignments/41")), 404, "WORK_ASSIGNMENT_NOT_FOUND");
		}

		@ParameterizedTest(name = "id={0}")
		@ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
		@DisplayName("잘못된 ID는 400 INVALID_REQUEST이고 저장소 조회 없음")
		void invalidId(String id) throws Exception {
			assertError(perform(get("/demo/api/employer/work-assignments/" + id)), 400, "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("저장된 시간대 해석 불가는 503 SERVICE_UNAVAILABLE")
		void invalidZone() throws Exception {
			given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(DemoWorkCompletionQueryServiceTest.Rows.detail(
				41, 7, "사과", "가", null, "Seoul", LocalDateTime.parse("2026-11-30T17:00"), null, null)));

			assertError(perform(get("/demo/api/employer/work-assignments/41")), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("DB 연결·잠금 오류는 503, 그 밖의 예외는 500이고 예외 메시지를 노출하지 않는다")
		void dataErrors() throws Exception {
			// 이미 예외로 stub된 메서드를 given()으로 다시 호출하면 stub 자체가 예외를 던지므로 순차 stub 한 번으로 둔다.
			given(repository.findForEmployer(anyLong())).willThrow(new DataAccessResourceFailureException(SECRET),
				new CannotAcquireLockException(SECRET), new IllegalStateException(SECRET));
			assertError(perform(get("/demo/api/employer/work-assignments")), 503, "SERVICE_UNAVAILABLE");
			assertError(perform(get("/demo/api/employer/work-assignments")), 503, "SERVICE_UNAVAILABLE");
			assertError(perform(get("/demo/api/employer/work-assignments")), 500, "INTERNAL_SERVER_ERROR");
		}
	}

	@Nested
	@DisplayName("선언 확인 (실행 검증 아님)")
	class Declarations {
		@Test
		@DisplayName("API·서비스·저장소·오류 처리기 모두 @Profile(\"farm-demo\"), 서비스는 readOnly 트랜잭션")
		void profiles() {
			for (Class<?> type : List.<Class<?>>of(DemoWorkCompletionApi.class, DemoWorkCompletionQueryService.class,
					DemoWorkCompletionRepository.class, DemoWorkCompletionErrorHandler.class)) {
				assertThat(type.getAnnotation(Profile.class).value()).as(type.getSimpleName()).containsExactly("farm-demo");
			}
			assertThat(DemoWorkCompletionQueryService.class.getAnnotation(Transactional.class).readOnly()).isTrue();
		}

		@Test
		@DisplayName("목록 SQL: posting.employer_id 필터, id desc, limit 100 / 상세 SQL: 고용주·근무 ID 둘 다 필터, 변경 행 left join")
		void sql() throws Exception {
			String list = DemoWorkCompletionRepository.class.getMethod("findForEmployer", long.class)
				.getAnnotation(Query.class).value().replaceAll("\\s+", " ");
			assertThat(list).contains("where posting.employer_id = :employerId", "order by assignment.id desc", "limit 100")
				.doesNotContain("completed_by", "password", "phone", "identity");
			String detail = DemoWorkCompletionRepository.class.getMethod("findDetailForEmployer", long.class, long.class)
				.getAnnotation(Query.class).value().replaceAll("\\s+", " ");
			assertThat(detail).contains("where posting.employer_id = :employerId and assignment.id = :assignmentId",
				"left join work_change change_record on change_record.assignment_id = assignment.id",
				"assignment.completed_at_utc as completedAtUtc").doesNotContain("completed_by", "password", "phone");
		}

		@Test
		@DisplayName("SecurityConfig: farm-demo 분기에 화면 permitAll·조회 API hasRole(EMPLOYER), 완료 POST는 분기 밖 hasRole(EMPLOYER), CSRF 예외 없음")
		void securityConfig() throws Exception {
			String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java")).replaceAll("\\s+", " ");
			int demo = config.indexOf("if (environment.matchesProfiles(\"farm-demo\")) {");
			String block = config.substring(demo, config.indexOf("}", demo));
			assertThat(block).contains("auth.requestMatchers(HttpMethod.GET, \"/demo/work-completion\").permitAll();",
				"auth.requestMatchers(HttpMethod.GET, \"/demo/api/employer/work-assignments\", "
					+ "\"/demo/api/employer/work-assignments/*\").hasRole(\"EMPLOYER\");");
			assertThat(block).doesNotContain("/api/work-assignments/*/completion");
			assertThat(config.substring(config.indexOf("}", demo))).contains(
				".requestMatchers(HttpMethod.POST, \"/api/work-assignments/*/completion\").hasRole(\"EMPLOYER\")");
			assertThat(config).doesNotContain("ignoringRequestMatchers", ".csrf(", "/demo/api/employer/work-assignments/**");
			assertThat(config).contains("\"{\\\"code\\\":\\\"EMPLOYER_ROLE_REQUIRED\\\",\\\"message\\\":\\\"고용주 계정만 근무를 완료할 수 있습니다.\\\"}\"");
		}
	}

	private void detailRow(LocalDateTime completedUtc, String changeType) {
		given(repository.findDetailForEmployer(EMPLOYER_ID, 41)).willReturn(Optional.of(DemoWorkCompletionQueryServiceTest.Rows.detail(
			41, 7, "사과 수확", "근로자 가", LocalDate.parse("2026-11-30"), "GMT+9", LocalDateTime.parse("2026-11-30T17:00"),
			completedUtc, changeType)));
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoStore(MockHttpServletResponse response) {
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertNoStore(response);
		JsonNode body = parse(response);
		assertThat(body.propertyNames()).containsExactly("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("jdbc", "s3cr3t", "Exception");
	}
}

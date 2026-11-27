package com.wonday.farm.demo.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.auth.FarmPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoMyApplicationFixtureApi·DemoMyApplicationPage + 실제 오류 처리기, 서비스는 mock.
// CSRF 검사·hasRole("WORKER")·세션·farm-demo 프로필 빈 등록은 Security 체인/컨텍스트 범위라 확인하지 않는다.
@DisplayName("farm-demo APP-04 시연 준비 API·화면 컨트롤러 (standalone MockMvc)")
class DemoMyApplicationFixtureApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t";
	private final DemoMyApplicationFixtureService service = mock(DemoMyApplicationFixtureService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoMyApplicationFixtureApi(service), new DemoMyApplicationPage())
		.setControllerAdvice(new DemoJobApplicationErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(5L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse call(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}
	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
	private static DemoMyApplicationFixtureService.FixtureResponse result(boolean created) {
		return new DemoMyApplicationFixtureService.FixtureResponse(4001, created, List.of(
			new DemoMyApplicationFixtureService.Scenario("확정 후 취소", 3061, 306, LocalDate.parse("2026-12-13"), 1, true, 53061L, "CANCELLED", false),
			new DemoMyApplicationFixtureService.Scenario("재지원의 과거 회차", 3091, 309, LocalDate.parse("2026-12-14"), 1, false, null, "NOT_SELECTED", false)));
	}
	private void expectError(int status, String code) throws Exception {
		var response = call(post("/demo/api/my-applications/fixtures"));
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(body(response).get("code").asString()).isEqualTo(code);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("s3cr3t", "jdbc:");
	}

	@Test
	@DisplayName("새 준비 201·no-store, 사례의 실제 ID·회차·현재 여부·확정 근무 ID·근무일 JSON")
	void created() throws Exception {
		given(service.prepare(5L)).willReturn(result(true));
		var response = call(post("/demo/api/my-applications/fixtures"));
		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = body(response);
		assertThat(json.get("jobPostingId").asLong()).isEqualTo(4001);
		assertThat(json.get("created").asBoolean()).isTrue();
		var first = json.get("scenarios").get(0);
		assertThat(first.get("label").asString()).isEqualTo("확정 후 취소");
		assertThat(first.get("applicationId").asLong()).isEqualTo(3061);
		assertThat(first.get("assignmentId").asLong()).isEqualTo(53061);
		assertThat(first.get("workDate").asString()).isEqualTo("2026-12-13");
		var second = json.get("scenarios").get(1);
		assertThat(second.get("current").asBoolean()).isFalse();
		assertThat(second.get("assignmentId").isNull()).isTrue();
	}

	@Test
	@DisplayName("오늘 데이터 재사용은 200")
	void reused() throws Exception {
		given(service.prepare(5L)).willReturn(result(false));
		var response = call(post("/demo/api/my-applications/fixtures"));
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(body(response).get("created").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("요청 본문·파라미터로 다른 회원·공고를 지정해도 principal의 회원 ID만 서비스에 전달")
	void principalOnly() throws Exception {
		given(service.prepare(5L)).willReturn(result(true));
		call(post("/demo/api/my-applications/fixtures").param("workerId", "6").param("jobPostingId", "1")
			.contentType("application/json").content("{\"workerId\":6,\"jobPostingId\":1,\"employerId\":2}"));
		verify(service).prepare(5L);
	}

	@Test
	@DisplayName("principal 없음 → 403 WORKER_ROLE_REQUIRED, 서비스 호출 없음 (실제 401·CSRF 거절은 Security 체인 범위)")
	void noPrincipal() throws Exception {
		SecurityContextHolder.clearContext();
		expectError(403, "WORKER_ROLE_REQUIRED");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("서비스 오류 매핑: AccessDenied 403, 고용주 계정 없음 409, 잠금 실패 503, 제약 충돌 409, DB 장애 503, 그 밖 500")
	void errors() throws Exception {
		willThrow(new AccessDeniedException(SECRET)).given(service).prepare(5L);
		expectError(403, "WORKER_ROLE_REQUIRED");
		willThrow(new ApplicationException(HttpStatus.CONFLICT, "DEMO_EMPLOYER_UNAVAILABLE", "demo-employer 확인")).given(service).prepare(5L);
		expectError(409, "DEMO_EMPLOYER_UNAVAILABLE");
		willThrow(ApplicationException.unavailable()).given(service).prepare(5L);
		expectError(503, "SERVICE_UNAVAILABLE");
		willThrow(new DataIntegrityViolationException(SECRET)).given(service).prepare(5L);
		expectError(409, "DEMO_FIXTURE_CONFLICT");
		willThrow(new DataAccessResourceFailureException(SECRET)).given(service).prepare(5L);
		expectError(503, "SERVICE_UNAVAILABLE");
		willThrow(new IllegalStateException(SECRET)).given(service).prepare(5L);
		expectError(500, "INTERNAL_SERVER_ERROR");
	}

	@Test
	@DisplayName("GET 준비 요청은 매핑되지 않음(405), 서비스 호출 없음")
	void getNotMapped() throws Exception {
		assertThat(call(get("/demo/api/my-applications/fixtures")).getStatus()).isEqualTo(405);
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("화면 GET /demo/my-applications: 200 HTML·no-store, 페이지 조회만으로 준비 서비스 호출 없음")
	void page() throws Exception {
		var response = call(get("/demo/my-applications"));
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("text/html");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>내 지원 내역 · 조건 비교</h1>");
		verifyNoInteractions(service);
	}
}

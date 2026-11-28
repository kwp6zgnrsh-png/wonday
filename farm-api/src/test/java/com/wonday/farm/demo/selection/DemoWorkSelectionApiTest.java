package com.wonday.farm.demo.selection;

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

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.exception.WorkSelectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoWorkSelectionApi + 실제 오류 처리기, 서비스는 mock.
// hasAnyRole·CSRF·farm-demo 프로필 빈 등록은 확인하지 않는다.
@DisplayName("farm-demo WORK-02 화면·시연 준비/조회 API (standalone MockMvc)")
class DemoWorkSelectionApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5 password=s3cr3t";
	private final DemoWorkSelectionFixtureService service = mock(DemoWorkSelectionFixtureService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoWorkSelectionApi(service))
		.setControllerAdvice(new DemoWorkSelectionErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(10L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse call(RequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
	private static DemoWorkSelectionFixtureService.FixtureResponse result(boolean created) {
		return new DemoWorkSelectionFixtureService.FixtureResponse(created, created ? 4201L : null,
			List.of(new DemoWorkSelectionFixtureService.Case("NORMAL", "정상 선정·같은 선정 재요청", 4201, 301, 1001, 123, "근로자",
				10, "고용주", LocalDate.parse("2026-12-05"), "OPEN", 1, 1, "PENDING", null, "PENDING")));
	}
	private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(JSON.readTree(text).get("code").asString()).isEqualTo(code);
		assertThat(text).doesNotContain("s3cr3t", "jdbc:");
	}

	@Test
	@DisplayName("준비 POST: 신규 201·재사용 200·no-store, 세션 ID만 전달(파라미터·본문 무시)")
	void prepare() throws Exception {
		given(service.prepare(10L)).willReturn(result(true));
		var created = call(post("/demo/api/work-selection/fixtures").param("actorId", "99").contentType("application/json").content("{\"actorId\":99}"));
		assertThat(created.getStatus()).isEqualTo(201);
		assertThat(created.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = JSON.readTree(created.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("preparedJobPostingId").asLong()).isEqualTo(4201);
		assertThat(json.get("cases").get(0).get("workDate").asString()).isEqualTo("2026-12-05");
		assertThat(json.get("cases").get(0).get("assignmentId").isNull()).isTrue();
		verify(service).prepare(10L);
		given(service.prepare(10L)).willReturn(result(false));
		assertThat(call(post("/demo/api/work-selection/fixtures")).getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("사례 GET: 200·no-store, 세션 ID로 조회, 준비 서비스 호출 없음")
	void list() throws Exception {
		given(service.list(10L)).willReturn(result(false));
		var response = call(get("/demo/api/work-selection/fixtures").param("actorId", "99"));
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		verify(service).list(10L);
		org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).prepare(org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	@DisplayName("화면 GET: 200 HTML·no-store, 화면만으로 서비스 호출 없음")
	void page() throws Exception {
		var response = call(get("/demo/work-selection"));
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>한 명씩 선정 · 변경 조건 재확인</h1>");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("principal 없음 → 403 FARM_ROLE_REQUIRED (GET·POST), 서비스 호출 없음")
	void noPrincipal() throws Exception {
		SecurityContextHolder.clearContext();
		error(call(post("/demo/api/work-selection/fixtures")), 403, "FARM_ROLE_REQUIRED");
		error(call(get("/demo/api/work-selection/fixtures")), 403, "FARM_ROLE_REQUIRED");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("오류 매핑: AccessDenied 403, 계정 부재 409, 저장 실패 503, DB·트랜잭션 503, 그 밖 500")
	void errors() throws Exception {
		Object[][] cases = {{new AccessDeniedException(SECRET), 403, "FARM_ROLE_REQUIRED"},
			{WorkSelectionException.conflict("DEMO_ACCOUNT_UNAVAILABLE", "계정"), 409, "DEMO_ACCOUNT_UNAVAILABLE"},
			{WorkSelectionException.unavailable(), 503, "SERVICE_UNAVAILABLE"},
			{new DataAccessResourceFailureException(SECRET), 503, "SERVICE_UNAVAILABLE"},
			{new CannotCreateTransactionException(SECRET), 503, "SERVICE_UNAVAILABLE"},
			{new IllegalStateException(SECRET), 500, "INTERNAL_SERVER_ERROR"}};
		for (var c : cases) {
			willThrow((RuntimeException) c[0]).given(service).prepare(10L);
			error(call(post("/demo/api/work-selection/fixtures")), (int) c[1], (String) c[2]);
		}
	}
}

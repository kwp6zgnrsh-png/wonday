package com.wonday.farm.demo.applicant;

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

// standalone MockMvc: 실제 DemoWorkApplicantApi + 실제 오류 처리기, 서비스는 mock.
// CSRF 거절·hasRole("EMPLOYER")·세션·farm-demo 프로필 빈 등록은 확인하지 않는다.
@DisplayName("farm-demo WORK-01 화면·시연 준비 API (standalone MockMvc)")
class DemoWorkApplicantApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t";
	private final DemoWorkApplicantFixtureService service = mock(DemoWorkApplicantFixtureService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoWorkApplicantApi(service))
		.setControllerAdvice(new DemoWorkApplicantErrorHandler())
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
	private static DemoWorkApplicantFixtureService.FixtureResponse result(boolean created) {
		return new DemoWorkApplicantFixtureService.FixtureResponse(4101, created,
			List.of(new DemoWorkApplicantFixtureService.Date(301, LocalDate.parse("2026-12-08"), "OPEN", 2)),
			List.of(new DemoWorkApplicantFixtureService.Case("지원 대기", 9011, 301, "시연 근로자 1", "PENDING", 1, true)));
	}
	private void expectError(int status, String code) throws Exception {
		var response = call(post("/demo/api/work-applicants/fixtures"));
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(JSON.readTree(text).get("code").asString()).isEqualTo(code);
		assertThat(text).doesNotContain("s3cr3t", "jdbc:");
	}

	@Test
	@DisplayName("신규 201·재사용 200·no-store, 세션 회원 ID만 전달(요청 파라미터·본문 무시), 날짜·사례 JSON")
	void prepare() throws Exception {
		given(service.prepare(10L)).willReturn(result(true));
		var created = call(post("/demo/api/work-applicants/fixtures").param("employerId", "99")
			.contentType("application/json").content("{\"employerId\":99}"));
		assertThat(created.getStatus()).isEqualTo(201);
		assertThat(created.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = JSON.readTree(created.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("schedules").get(0).get("workDate").asString()).isEqualTo("2026-12-08");
		assertThat(json.get("scenarios").get(0).get("applicationId").asLong()).isEqualTo(9011);
		assertThat(json.toString()).doesNotContain("password", "phone");
		verify(service).prepare(10L);
		given(service.prepare(10L)).willReturn(result(false));
		assertThat(call(post("/demo/api/work-applicants/fixtures")).getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("principal 없음 → 403 EMPLOYER_ROLE_REQUIRED, 서비스 호출 없음")
	void noPrincipal() throws Exception {
		SecurityContextHolder.clearContext();
		expectError(403, "EMPLOYER_ROLE_REQUIRED");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("오류 매핑: AccessDenied 403, 저장 실패 503, DB·트랜잭션 503, 그 밖 500, 성공 응답 없음")
	void errors() throws Exception {
		willThrow(new AccessDeniedException(SECRET)).given(service).prepare(10L);
		expectError(403, "EMPLOYER_ROLE_REQUIRED");
		willThrow(ApplicationException.unavailable()).given(service).prepare(10L);
		expectError(503, "SERVICE_UNAVAILABLE");
		willThrow(new DataAccessResourceFailureException(SECRET)).given(service).prepare(10L);
		expectError(503, "SERVICE_UNAVAILABLE");
		willThrow(new CannotCreateTransactionException(SECRET)).given(service).prepare(10L);
		expectError(503, "SERVICE_UNAVAILABLE");
		willThrow(new IllegalStateException(SECRET)).given(service).prepare(10L);
		expectError(500, "INTERNAL_SERVER_ERROR");
	}

	@Test
	@DisplayName("화면 GET: 200 HTML·no-store, 페이지 조회만으로 준비 서비스 호출 없음, 준비 GET은 405")
	void page() throws Exception {
		var response = call(get("/demo/work-applicants"));
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>날짜별 지원자 조회</h1>");
		assertThat(call(get("/demo/api/work-applicants/fixtures")).getStatus()).isEqualTo(405);
		verifyNoInteractions(service);
	}
}

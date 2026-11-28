package com.wonday.farm.demo.earlyend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.work.exception.WorkCompletionException;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoWorkEarlyEndApi + 실제 오류 처리기, 준비 서비스는 mock. 프로필·Security·CSRF는 통과하지 않는다.
@DisplayName("farm-demo CHANGE-03 화면·시연 준비 API (standalone MockMvc)")
class DemoWorkEarlyEndApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5 password=s3cr3t";
	private final DemoWorkEarlyEndFixtureService service = mock(DemoWorkEarlyEndFixtureService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoWorkEarlyEndApi(service))
		.setControllerAdvice(new DemoWorkEarlyEndErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(10L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse prepare() throws Exception { return mvc.perform(post("/demo/api/work-early-end/fixtures")).andReturn().getResponse(); }
	private static DemoWorkEarlyEndFixtureService.FixtureResponse result(boolean created) {
		return new DemoWorkEarlyEndFixtureService.FixtureResponse(created, 4201L, List.of(new DemoWorkEarlyEndFixtureService.FixtureCase(
			"NORMAL", "최초 조기 종료 200·동일 재요청", 7001L, 301L, "CONFIRMED", OffsetDateTime.parse("2026-11-29T00:00+09:00"),
			OffsetDateTime.parse("2026-11-29T23:59:59.999999+09:00"), OffsetDateTime.parse("2026-11-30T23:59:59.999999+09:00"),
			new WorkEarlyEndRequest(OffsetDateTime.parse("2026-11-29T00:29:00.123456+09:00"), "WEATHER", "RAIN", "기상으로 작업을 조기 종료합니다."))));
	}
	private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		var json = JSON.readTree(text);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(text).doesNotContain("s3cr3t", "jdbc:", "Exception");
	}

	@Test
	@DisplayName("화면 GET: HTML·no-store, 준비 서비스 미호출")
	void page() throws Exception {
		var response = mvc.perform(get("/demo/work-early-end")).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("text/html");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<script>");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("신규 201·재사용 200, no-store, 응답 필드(소수초 6자리 보존)")
	void createdAndReused() throws Exception {
		given(service.prepare(10L)).willReturn(result(true));
		var created = prepare();
		assertThat(created.getStatus()).isEqualTo(201);
		assertThat(created.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = JSON.readTree(created.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("created").asBoolean()).isTrue();
		assertThat(json.get("jobPostingId").asLong()).isEqualTo(4201L);
		var item = json.get("cases").get(0);
		assertThat(item.get("assignmentId").asLong()).isEqualTo(7001L);
		assertThat(item.get("agreedEndAt").asString()).isEqualTo("2026-11-29T23:59:59.999999+09:00");
		assertThat(item.get("recordingDeadline").asString()).isEqualTo("2026-11-30T23:59:59.999999+09:00");
		assertThat(item.get("suggestedRequest").get("actualEndAt").asString()).isEqualTo("2026-11-29T00:29:00.123456+09:00");
		given(service.prepare(10L)).willReturn(result(false));
		assertThat(prepare().getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("403·409·503·500 실패를 성공으로 표시하지 않음")
	void failures() throws Exception {
		willThrow(new AccessDeniedException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 403, "EMPLOYER_ROLE_REQUIRED");
		willThrow(WorkEarlyEndException.conflict("DEMO_ACCOUNT_UNAVAILABLE", "farm-demo의 demo-worker 계정을 확인해 주세요."))
			.given(service).prepare(anyLong());
		error(prepare(), 409, "DEMO_ACCOUNT_UNAVAILABLE");
		willThrow(WorkEarlyEndException.unavailable()).given(service).prepare(anyLong());
		error(prepare(), 503, "SERVICE_UNAVAILABLE");
		willThrow(WorkCompletionException.unavailable()).given(service).prepare(anyLong());
		error(prepare(), 503, "SERVICE_UNAVAILABLE");
		willThrow(new DataAccessResourceFailureException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 503, "SERVICE_UNAVAILABLE");
		willThrow(new IllegalStateException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 500, "INTERNAL_SERVER_ERROR");
		SecurityContextHolder.clearContext();
		error(prepare(), 403, "EMPLOYER_ROLE_REQUIRED");
	}
}

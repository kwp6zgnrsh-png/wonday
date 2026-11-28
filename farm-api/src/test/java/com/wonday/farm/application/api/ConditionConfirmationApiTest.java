package com.wonday.farm.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.application.dto.ConditionConfirmation;
import com.wonday.farm.application.dto.ConditionConfirmationResponse;
import com.wonday.farm.application.service.ConditionConfirmationService;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.exception.WorkSelectionErrorHandler;
import com.wonday.farm.work.exception.WorkSelectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 ConditionConfirmationApi + 실제 WorkSelectionErrorHandler, 서비스는 mock.
// Security 체인·세션·CSRF·hasRole("WORKER")은 통과하지 않는다.
@DisplayName("WORK-04 조건 재확인 API (standalone MockMvc)")
class ConditionConfirmationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/api/me/applications/501/condition-confirmation";
	private static final String SECRET = "SQL duplicate key uq_application_acknowledgement_version password=s3cr3t";
	private final ConditionConfirmationService service = mock(ConditionConfirmationService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConditionConfirmationApi(service))
		.setControllerAdvice(new WorkSelectionErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(123L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse send(String path, String body) throws Exception {
		return mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse();
	}
	private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(JSON.readTree(text).get("code").asString()).isEqualTo(code);
		assertThat(text).doesNotContain("s3cr3t", "uq_application", "SQL");
	}

	@Test
	@DisplayName("200·no-store, 세션 근로자 ID 전달, 재확인 JSON(+09:00·마이크로초), 최초 지원 확인은 null 필드")
	void success() throws Exception {
		given(service.confirm(123L, 501L, new TermsVersionRequest(3))).willReturn(new ConditionConfirmationResponse(501, 1, "PENDING",
			new ConditionConfirmation("RECONFIRMATION", 3, true, 9001L, 3, OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00"))));
		var response = send(PATH, "{\"termsVersion\":3,\"workerId\":999,\"acknowledgedAt\":\"2020-01-01T00:00:00Z\"}");
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		var info = json.get("conditionConfirmation");
		assertThat(json.get("status").asString()).isEqualTo("PENDING");
		assertThat(info.get("source").asString()).isEqualTo("RECONFIRMATION");
		assertThat(info.get("latestTermsConfirmed").asBoolean()).isTrue();
		assertThat(info.get("acknowledgedAt").asString()).isEqualTo("2026-11-28T15:00:00.123456+09:00");
		assertThat(info.propertyNames()).containsExactlyInAnyOrder("source", "confirmedTermsVersion", "latestTermsConfirmed",
			"acknowledgementId", "acknowledgedTermsVersion", "acknowledgedAt");

		given(service.confirm(123L, 501L, new TermsVersionRequest(3))).willReturn(new ConditionConfirmationResponse(501, 1, "PENDING",
			new ConditionConfirmation("INITIAL_APPLICATION", 3, true, null, null, null)));
		var initial = JSON.readTree(send(PATH, "{\"termsVersion\":3}").getContentAsString(StandardCharsets.UTF_8)).get("conditionConfirmation");
		assertThat(initial.get("acknowledgementId").isNull()).isTrue();
		assertThat(initial.get("acknowledgedTermsVersion").isNull()).isTrue();
		assertThat(initial.get("acknowledgedAt").isNull()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"termsVersion\":3.0}", "{\"termsVersion\":\"3\"}", "{\"termsVersion\":[3]}", "{\"termsVersion\":2147483648}",
		"{\"termsVersion\":0}", "{\"termsVersion\":null}", "{}", ""})
	@DisplayName("잘못된 본문 → 400 INVALID_REQUEST, 서비스 호출 없음")
	void badBody(String body) throws Exception {
		error(send(PATH, body), 400, "INVALID_REQUEST");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("text/plain → 415 INVALID_REQUEST (no-store), 서비스 호출 없음")
	void mediaType() throws Exception {
		error(mvc.perform(post(PATH).contentType("text/plain").content("3")).andReturn().getResponse(), 415, "INVALID_REQUEST");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("잘못된 경로 ID → 400, principal 없음 → 403 WORKER_ROLE_REQUIRED")
	void inputAndPrincipal() throws Exception {
		error(send("/api/me/applications/0/condition-confirmation", "{\"termsVersion\":3}"), 400, "INVALID_REQUEST");
		SecurityContextHolder.clearContext();
		error(send(PATH, "{\"termsVersion\":3}"), 403, "WORKER_ROLE_REQUIRED");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("오류 매핑: AccessDenied는 /api/me 경로라 WORKER_ROLE_REQUIRED, 404·409·무결성 409·503·500")
	void errors() throws Exception {
		Object[][] cases = {{new AccessDeniedException(SECRET), 403, "WORKER_ROLE_REQUIRED"},
			{WorkSelectionException.missing("APPLICATION_NOT_FOUND", "본인의 지원을 찾을 수 없습니다."), 404, "APPLICATION_NOT_FOUND"},
			{WorkSelectionException.conflict("APPLICATION_NOT_PENDING", "대기"), 409, "APPLICATION_NOT_PENDING"},
			{WorkSelectionException.conflict("TERMS_VERSION_CONFLICT", "버전"), 409, "TERMS_VERSION_CONFLICT"},
			{new DataIntegrityViolationException(SECRET), 409, "WORK_STATE_CONFLICT"},
			{new DataAccessResourceFailureException(SECRET), 503, "SERVICE_UNAVAILABLE"},
			{new IllegalStateException(SECRET), 500, "INTERNAL_SERVER_ERROR"}};
		for (var c : cases) {
			willThrow((RuntimeException) c[0]).given(service).confirm(anyLong(), anyLong(), any());
			error(send(PATH, "{\"termsVersion\":3}"), (int) c[1], (String) c[2]);
		}
	}
}

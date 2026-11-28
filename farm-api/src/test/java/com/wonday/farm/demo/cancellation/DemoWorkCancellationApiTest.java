package com.wonday.farm.demo.cancellation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.exception.WorkCancellationException;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoWorkCancellationApi + 실제 오류 처리기, 준비 서비스는 mock.
// 프로필 활성화·Security 체인·CSRF는 통과하지 않는다(프로필은 선언만 별도 확인).
@DisplayName("farm-demo CHANGE-01·02 화면·시연 준비 API (standalone MockMvc)")
class DemoWorkCancellationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SECRET = "jdbc:mysql://10.0.0.5 password=s3cr3t";
	private final DemoWorkCancellationFixtureService service = mock(DemoWorkCancellationFixtureService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoWorkCancellationApi(service))
		.setControllerAdvice(new DemoWorkCancellationErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(10L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse prepare() throws Exception {
		return mvc.perform(post("/demo/api/work-cancellation/fixtures")).andReturn().getResponse();
	}
	private static DemoWorkCancellationFixtureService.FixtureResponse result(boolean created) {
		return new DemoWorkCancellationFixtureService.FixtureResponse(created, 4201L, List.of(new DemoWorkCancellationFixtureService.FixtureCase(
			"NORMAL", "최초 취소·동일 재요청·다른 사유 409", 4201L, 301L, 7001L, "CONFIRMED", OffsetDateTime.parse("2026-12-10T09:00+09:00"),
			new WorkCancellationRequest("WEATHER", "RAIN", "강수로 작업을 취소합니다."))));
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
	@DisplayName("화면 GET: HTML, no-store")
	void page() throws Exception {
		var response = mvc.perform(get("/demo/work-cancellation")).andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("text/html");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("개인별 시작 전 취소 확인");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("신규 준비 201, 재사용 200, 세션 회원 ID 전달, no-store, 응답 필드")
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
		assertThat(item.get("workStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(item.get("agreedStartAt").asString()).isEqualTo("2026-12-10T09:00:00+09:00");
		assertThat(item.get("suggestedRequest").get("reasonCode").asString()).isEqualTo("WEATHER");
		assertThat(item.get("suggestedRequest").get("weatherReasonCode").asString()).isEqualTo("RAIN");

		given(service.prepare(10L)).willReturn(result(false));
		var reused = prepare();
		assertThat(reused.getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(reused.getContentAsString(StandardCharsets.UTF_8)).get("created").asBoolean()).isFalse();
		verify(service, org.mockito.Mockito.times(2)).prepare(10L);
	}

	@Test
	@DisplayName("principal 없음·역할 거절 403")
	void forbidden() throws Exception {
		willThrow(new AccessDeniedException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 403, "EMPLOYER_ROLE_REQUIRED");
		SecurityContextHolder.clearContext();
		error(prepare(), 403, "EMPLOYER_ROLE_REQUIRED");
	}

	@Test
	@DisplayName("준비 실패는 성공으로 표시하지 않음: 선정 409·준비 불가 503·DB 503·예상 외 500")
	void failures() throws Exception {
		willThrow(WorkSelectionException.conflict("DEMO_ACCOUNT_UNAVAILABLE", "farm-demo의 demo-employer·demo-worker 계정을 확인해 주세요."))
			.given(service).prepare(anyLong());
		error(prepare(), 409, "DEMO_ACCOUNT_UNAVAILABLE");
		willThrow(WorkCancellationException.unavailable()).given(service).prepare(anyLong());
		error(prepare(), 503, "SERVICE_UNAVAILABLE");
		willThrow(new DataAccessResourceFailureException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 503, "SERVICE_UNAVAILABLE");
		willThrow(new IllegalStateException(SECRET)).given(service).prepare(anyLong());
		error(prepare(), 500, "INTERNAL_SERVER_ERROR");
	}
}

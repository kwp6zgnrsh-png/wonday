package com.wonday.farm.work.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.dto.WorkSelectionResponse;
import com.wonday.farm.work.exception.WorkSelectionErrorHandler;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.service.WorkSelectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 WorkSelectionApi + 실제 WorkSelectionErrorHandler, 서비스는 mock.
// 실제 Security 필터 체인·세션 쿠키·CSRF·hasRole 규칙은 통과하지 않는다(익명 401·CSRF 403 미검증).
@DisplayName("WORK-02 선정 API (standalone MockMvc)")
class WorkSelectionApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/api/job-postings/101/schedules/201/applications/501/selection";
	private static final String SECRET = "SQL [insert into work_assignment] jdbc:mysql://10.0.0.5 password=s3cr3t 01012345678";
	private final WorkSelectionService service = mock(WorkSelectionService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkSelectionApi(service))
		.setControllerAdvice(new WorkSelectionErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

	@BeforeEach
	void signIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(10L);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}
	@AfterEach
	void clear() { SecurityContextHolder.clearContext(); }

	private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
	private MockHttpServletResponse send(String path, String body) throws Exception {
		return call(post(path).contentType("application/json").content(body));
	}
	private static WorkSelectionResponse body(String workStatus, OffsetDateTime completedAt, Integer version, OffsetDateTime confirmedAt) {
		return new WorkSelectionResponse(501, 201, 123, "SELECTED", 7001, version, confirmedAt, workStatus, completedAt);
	}
	private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		var json = JSON.readTree(text);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(text).doesNotContain("s3cr3t", "jdbc:", "insert into", "01012345678", "Exception");
	}

	@Test
	@DisplayName("신규 201·재요청 200, no-store, 세션 principal ID와 경로 ID·버전 전달, JSON 표현(+09:00·마이크로초·null)")
	void success() throws Exception {
		var confirmed = OffsetDateTime.parse("2026-11-28T15:00:00.123456+09:00");
		given(service.select(10L, 101L, 201L, 501L, new TermsVersionRequest(3)))
			.willReturn(new WorkSelectionService.Result(false, body("CONFIRMED", null, 3, confirmed)));
		var created = send(PATH, "{\"termsVersion\":3}");
		assertThat(created.getStatus()).isEqualTo(201);
		assertThat(created.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(created.getContentType()).startsWith("application/json");
		var json = JSON.readTree(created.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("confirmedAt").asString()).isEqualTo("2026-11-28T15:00:00.123456+09:00");
		assertThat(json.get("completedAt").isNull()).isTrue();
		assertThat(json.get("status").asString()).isEqualTo("SELECTED");
		assertThat(json.get("workStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(json.get("agreedTermsVersion").asInt()).isEqualTo(3);
		assertThat(json.get("assignmentId").asLong()).isEqualTo(7001);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("applicationId", "workScheduleId", "workerId", "status",
			"assignmentId", "agreedTermsVersion", "confirmedAt", "workStatus", "completedAt");

		given(service.select(10L, 101L, 201L, 501L, new TermsVersionRequest(3))).willReturn(new WorkSelectionService.Result(true,
			body("COMPLETED", OffsetDateTime.parse("2026-11-29T18:00:00+09:00"), null, null)));
		var replay = send(PATH, "{\"termsVersion\":3}");
		assertThat(replay.getStatus()).isEqualTo(200);
		assertThat(replay.getHeader("Cache-Control")).isEqualTo("no-store");
		var again = JSON.readTree(replay.getContentAsString(StandardCharsets.UTF_8));
		assertThat(again.get("completedAt").asString()).isEqualTo("2026-11-29T18:00:00+09:00");
		assertThat(again.get("agreedTermsVersion").isNull()).isTrue();
		assertThat(again.get("confirmedAt").isNull()).isTrue();
	}

	@Test
	@DisplayName("본문의 employerId·workerId·시각은 무시, 세션 ID 10만 전달")
	void ignoresClientFields() throws Exception {
		given(service.select(anyLong(), anyLong(), anyLong(), anyLong(), any()))
			.willReturn(new WorkSelectionService.Result(false, body("CONFIRMED", null, 3, null)));
		send(PATH, "{\"termsVersion\":3,\"employerId\":99,\"workerId\":7,\"confirmedAt\":\"2020-01-01T00:00:00Z\"}");
		var captor = ArgumentCaptor.forClass(TermsVersionRequest.class);
		verify(service).select(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(101L),
			org.mockito.ArgumentMatchers.eq(201L), org.mockito.ArgumentMatchers.eq(501L), captor.capture());
		assertThat(captor.getValue()).isEqualTo(new TermsVersionRequest(3));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"termsVersion\":3.0}", "{\"termsVersion\":3.5}", "{\"termsVersion\":\"3\"}", "{\"termsVersion\":[3]}",
		"{\"termsVersion\":true}", "{\"termsVersion\":2147483648}", "{\"termsVersion\":0}", "{\"termsVersion\":-1}",
		"{\"termsVersion\":null}", "{}", "", "[3]", "{\"termsVersion\":3", "{\"termsVersion\":1e2}"})
	@DisplayName("잘못된 본문(소수·숫자 문자열·배열·불리언·범위 초과·0·음수·null·누락·빈 본문·깨진 JSON·지수) → 400 INVALID_REQUEST, 서비스 호출 없음")
	void badBody(String body) throws Exception {
		error(send(PATH, body), 400, "INVALID_REQUEST");
		verifyNoInteractions(service);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/job-postings/0/schedules/201/applications/501/selection",
		"/api/job-postings/101/schedules/-1/applications/501/selection",
		"/api/job-postings/101/schedules/201/applications/01/selection",
		"/api/job-postings/abc/schedules/201/applications/501/selection",
		"/api/job-postings/101/schedules/201/applications/99999999999999999999/selection"})
	@DisplayName("잘못된 경로 ID(0·음수·앞자리 0·문자·범위 초과) → 400 INVALID_REQUEST")
	void badPath(String path) throws Exception {
		error(send(path, "{\"termsVersion\":3}"), 400, "INVALID_REQUEST");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("application/json 이외 형식 → 415 INVALID_REQUEST (no-store), 서비스 호출 없음")
	void mediaType() throws Exception {
		error(call(post(PATH).contentType("text/plain").content("{\"termsVersion\":3}")), 415, "INVALID_REQUEST");
		error(call(post(PATH).contentType("application/x-www-form-urlencoded").content("termsVersion=3")), 415, "INVALID_REQUEST");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("principal 없음 → 403 EMPLOYER_ROLE_REQUIRED (standalone, Security 체인의 익명 401과 다름), 서비스 호출 없음")
	void noPrincipal() throws Exception {
		SecurityContextHolder.clearContext();
		error(send(PATH, "{\"termsVersion\":3}"), 403, "EMPLOYER_ROLE_REQUIRED");
		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("서비스 오류 매핑: 403·404·409·DB 무결성 409·503·500, 내부 메시지·SQL·개인정보 비노출")
	void errors() throws Exception {
		String[][] cases = {{"403", "EMPLOYER_ROLE_REQUIRED"}, {"404", "JOB_POSTING_NOT_FOUND"}, {"404", "WORK_SCHEDULE_NOT_FOUND"},
			{"404", "APPLICATION_NOT_FOUND"}, {"409", "TERMS_VERSION_CONFLICT"}, {"409", "CONDITION_RECONFIRMATION_REQUIRED"},
			{"409", "CAPACITY_FULL"}, {"409", "SELECTION_NOT_ALLOWED"}, {"409", "WORK_ASSIGNMENT_CANCELLED"},
			{"409", "WORK_STATE_CONFLICT"}, {"503", "SERVICE_UNAVAILABLE"}, {"503", "SERVICE_UNAVAILABLE"},
			{"503", "SERVICE_UNAVAILABLE"}, {"500", "INTERNAL_SERVER_ERROR"}};
		RuntimeException[] thrown = {new AccessDeniedException(SECRET), WorkSelectionException.missing("JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다."),
			WorkSelectionException.missing("WORK_SCHEDULE_NOT_FOUND", "일정"), WorkSelectionException.missing("APPLICATION_NOT_FOUND", "지원"),
			WorkSelectionException.conflict("TERMS_VERSION_CONFLICT", "버전"), WorkSelectionException.conflict("CONDITION_RECONFIRMATION_REQUIRED", "재확인"),
			WorkSelectionException.conflict("CAPACITY_FULL", "정원"), WorkSelectionException.conflict("SELECTION_NOT_ALLOWED", "상태"),
			WorkSelectionException.conflict("WORK_ASSIGNMENT_CANCELLED", "취소"), new DataIntegrityViolationException(SECRET),
			new DataAccessResourceFailureException(SECRET), new CannotCreateTransactionException(SECRET), new QueryTimeoutException(SECRET),
			new IllegalStateException(SECRET)};
		for (int i = 0; i < thrown.length; i++) {
			willThrow(thrown[i]).given(service).select(anyLong(), anyLong(), anyLong(), anyLong(), any());
			error(send(PATH, "{\"termsVersion\":3}"), Integer.parseInt(cases[i][0]), cases[i][1]);
		}
	}

	@Test
	@DisplayName("GET은 이 경로에서 처리되지 않음(405), 서비스 호출 없음")
	void otherMethods() throws Exception {
		var get = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(PATH));
		assertThat(get.getStatus()).isEqualTo(405);
		verify(service, never()).select(anyLong(), anyLong(), anyLong(), anyLong(), any());
	}
}

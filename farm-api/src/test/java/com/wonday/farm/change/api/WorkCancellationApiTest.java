package com.wonday.farm.change.api;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkCancellationErrorHandler;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.service.WorkCancellationService;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

// standalone MockMvc: 실제 WorkCancellationApi + 실제 WorkCancellationErrorHandler + 실제 WorkCancellationService, 기록기만 mock.
// 실제 Security 필터 체인·세션 쿠키·CSRF·hasRole·익명 401·Boot /error는 통과하지 않는다(미검증).
@DisplayName("CHANGE-01·02 취소 API (standalone MockMvc)")
class WorkCancellationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/api/work-assignments/31/cancellation";
	private static final String SECRET = "SQL [insert into work_change] jdbc:mysql://10.0.0.5 password=s3cr3t 01012345678";
	private final WorkChangeRecorder recorder = mock(WorkChangeRecorder.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkCancellationApi(new WorkCancellationService(recorder)))
		.setControllerAdvice(new WorkCancellationErrorHandler())
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
	private static WorkChange stored(long id, Reason reason, WeatherReason weather, String detail, long version) {
		var change = change(id, 31L, Type.CANCELLED, reason, weather, detail,
			LocalDateTime.parse("2026-11-28T06:00:00.123456"), LocalDateTime.parse("2026-11-28T06:00:00.123456"));
		org.springframework.test.util.ReflectionTestUtils.setField(change, "sourceVersion", version);
		return change;
	}
	private void returns(WorkChange change, String zone) {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any()))
			.willReturn(new WorkChangeRecorder.RecordedCancellation(change, zone));
	}
	private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("application/json");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		var json = JSON.readTree(text);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(text).doesNotContain("s3cr3t", "jdbc:", "insert into", "01012345678", "Exception", "SECRET-INPUT", "<script>");
	}

	@Test
	@DisplayName("성공 200: 세션 회원 ID 전달, 정의한 JSON 필드, 문자열 버전, +09:00 시각, no-store")
	void success() throws Exception {
		returns(stored(77L, Reason.WEATHER, WeatherReason.RAIN, "비가 옵니다", 1), "GMT+9");
		var response = send(PATH, "{\"reasonCode\":\"WEATHER\",\"weatherReasonCode\":\"RAIN\",\"reasonDetail\":\" 비가 옵니다 \"}");
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("application/json");
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("assignmentId", "changeId", "changeType", "reasonCode",
			"weatherReasonCode", "reasonDetail", "effectiveAt", "recordedAt", "updatedAt", "sourceVersion");
		assertThat(json.get("assignmentId").asLong()).isEqualTo(31L);
		assertThat(json.get("changeId").asLong()).isEqualTo(77L);
		assertThat(json.get("changeType").asString()).isEqualTo("CANCELLED");
		assertThat(json.get("reasonCode").asString()).isEqualTo("WEATHER");
		assertThat(json.get("weatherReasonCode").asString()).isEqualTo("RAIN");
		assertThat(json.get("reasonDetail").asString()).isEqualTo("비가 옵니다");
		assertThat(json.get("sourceVersion").isString()).isTrue();
		assertThat(json.get("sourceVersion").asString()).isEqualTo("1");
		for (var key : List.of("effectiveAt", "recordedAt", "updatedAt")) {
			assertThat(json.get(key).asString()).as(key).isEqualTo("2026-11-28T15:00:00.123456+09:00");
		}
		verify(recorder).cancelBeforeStart(10L, 31L, Reason.WEATHER, WeatherReason.RAIN, "비가 옵니다");
	}

	@Test
	@DisplayName("성공 200: 사유·설명 null 응답, 다른 시간대 오프셋")
	void nulls() throws Exception {
		returns(stored(78L, Reason.OTHER, null, null, 12), "UTC");
		var response = send(PATH, "{\"reasonCode\":\"OTHER\",\"reasonDetail\":\"   \"}");
		assertThat(response.getStatus()).isEqualTo(200);
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("weatherReasonCode").isNull()).isTrue();
		assertThat(json.get("reasonDetail").isNull()).isTrue();
		assertThat(json.get("sourceVersion").asString()).isEqualTo("12");
		assertThat(json.get("effectiveAt").asString()).isEqualTo("2026-11-28T06:00:00.123456Z");
		verify(recorder).cancelBeforeStart(10L, 31L, Reason.OTHER, null, null);
	}

	@Test
	@DisplayName("클라이언트가 보낸 고용주 ID·취소 시각·변경 ID는 사용하지 않음")
	void clientCannotChooseActorOrTime() throws Exception {
		returns(stored(77L, Reason.OTHER, null, null, 1), "GMT+9");
		var response = send(PATH, "{\"reasonCode\":\"OTHER\",\"employerId\":999,\"actorId\":999,\"effectiveAt\":\"2020-01-01T00:00:00Z\","
			+ "\"recordedAt\":\"2020-01-01T00:00:00Z\",\"changeId\":1,\"sourceVersion\":\"9\"}");
		assertThat(response.getStatus()).isEqualTo(200);
		verify(recorder).cancelBeforeStart(10L, 31L, Reason.OTHER, null, null);
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("effectiveAt").asString()).startsWith("2026-11-28T15:00");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "+1", "1.5", "1e3", "abc", "01", "9223372036854775808", "%20"})
	@DisplayName("잘못된 경로 ID 400")
	void invalidPathId(String id) throws Exception {
		error(send("/api/work-assignments/" + id + "/cancellation", "{\"reasonCode\":\"OTHER\"}"), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{", "not json", "[]", "\"OTHER\"", "null",
		"{\"reasonCode\":1}", "{\"reasonCode\":true}", "{\"reasonCode\":[\"OTHER\"]}", "{\"reasonCode\":{\"v\":\"OTHER\"}}",
		"{\"reasonCode\":\"WEATHER\",\"weatherReasonCode\":1}", "{\"reasonCode\":\"OTHER\",\"reasonDetail\":123}",
		"{\"reasonCode\":\"OTHER\",\"reasonDetail\":false}",
		"{}", "{\"reasonCode\":null}", "{\"reasonCode\":\"\"}", "{\"reasonCode\":\"weather\",\"weatherReasonCode\":\"RAIN\"}",
		"{\"reasonCode\":\"WEATHER\"}", "{\"reasonCode\":\"WEATHER\",\"weatherReasonCode\":\"\"}",
		"{\"reasonCode\":\"WEATHER\",\"weatherReasonCode\":\"SNOW\"}", "{\"reasonCode\":\"OTHER\",\"weatherReasonCode\":\"RAIN\"}",
		"{\"reasonCode\":\"FARM_CIRCUMSTANCES\",\"weatherReasonCode\":\"HEAT\"}",
		"{\"reasonCode\":\"<script>SECRET-INPUT</script>\"}"})
	@DisplayName("본문 누락·JSON 형식·자동 변환·사유 조합 오류 400 (입력 원문 비노출)")
	void invalidBody(String body) throws Exception {
		error(send(PATH, body), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("본문 없는 요청 400")
	void noBody() throws Exception {
		error(call(post(PATH).contentType("application/json")), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("설명 500 코드 포인트 허용, 501 거절(잘라 저장하지 않음)")
	void detailLength() throws Exception {
		returns(stored(77L, Reason.OTHER, null, "😀".repeat(500), 1), "GMT+9");
		assertThat(send(PATH, "{\"reasonCode\":\"OTHER\",\"reasonDetail\":\"" + "😀".repeat(500) + "\"}").getStatus()).isEqualTo(200);
		verify(recorder).cancelBeforeStart(10L, 31L, Reason.OTHER, null, "😀".repeat(500));
		var response = send(PATH, "{\"reasonCode\":\"OTHER\",\"reasonDetail\":\"" + "가".repeat(501) + "\"}");
		error(response, 400, "INVALID_REQUEST");
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("가가");
	}

	@Test
	@DisplayName("다른 소유자·없는 근무 404")
	void notFound() throws Exception {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any())).willThrow(WorkCancellationException.notFound());
		error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 404, "WORK_ASSIGNMENT_NOT_FOUND");
	}

	@ParameterizedTest
	@ValueSource(strings = {"WORK_ALREADY_STARTED", "WORK_ALREADY_COMPLETED", "WORK_ALREADY_EARLY_ENDED", "CANCELLATION_REASON_CONFLICT"})
	@DisplayName("상태 충돌 409 코드와 메시지")
	void conflicts(String code) throws Exception {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any()))
			.willThrow(WorkCancellationException.conflict(code, "메시지-" + code));
		var response = send(PATH, "{\"reasonCode\":\"OTHER\"}");
		error(response, 409, code);
		assertThat(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("message").asString()).isEqualTo("메시지-" + code);
	}

	@Test
	@DisplayName("DB 무결성 충돌 409 WORK_STATE_CONFLICT, SQL 비노출")
	void integrity() throws Exception {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any())).willThrow(new DataIntegrityViolationException(SECRET));
		error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 409, "WORK_STATE_CONFLICT");
	}

	@Test
	@DisplayName("역할 거절 403, principal 없음 403 (실제 체인의 익명 401은 미검증)")
	void forbidden() throws Exception {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any())).willThrow(new AccessDeniedException(SECRET));
		error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 403, "EMPLOYER_ROLE_REQUIRED");
		SecurityContextHolder.clearContext();
		error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 403, "EMPLOYER_ROLE_REQUIRED");
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "application/xml", "application/octet-stream"})
	@DisplayName("지원하지 않는 Content-Type 415 INVALID_REQUEST JSON")
	void mediaType(String type) throws Exception {
		error(call(post(PATH).contentType(type).content("reasonCode=OTHER")), 415, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("서비스 불가 503")
	void unavailable() throws Exception {
		for (RuntimeException failure : new RuntimeException[] {InsuranceApiException.unavailable(), WorkCancellationException.unavailable(),
				new DataAccessResourceFailureException(SECRET), new QueryTimeoutException(SECRET), new CannotCreateTransactionException(SECRET)}) {
			org.mockito.BDDMockito.willThrow(failure).given(recorder).cancelBeforeStart(anyLong(), anyLong(), any(), any(), any());
			error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 503, "SERVICE_UNAVAILABLE");
		}
	}

	@Test
	@DisplayName("예상하지 못한 오류 500, 내부 메시지 비노출")
	void unexpected() throws Exception {
		given(recorder.cancelBeforeStart(anyLong(), anyLong(), any(), any(), any())).willThrow(new IllegalStateException(SECRET));
		error(send(PATH, "{\"reasonCode\":\"OTHER\"}"), 500, "INTERNAL_SERVER_ERROR");
	}

	@Test
	@DisplayName("GET은 매핑 없음 405, 기록기 미호출")
	void getNotAllowed() throws Exception {
		assertThat(call(get(PATH)).getStatus()).isEqualTo(405);
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("API 선언: consumes 없음(415를 전용 처리기로), 경로·응답 형식")
	void declaration() throws Exception {
		var method = WorkCancellationApi.class.getMethod("cancel", FarmPrincipal.class, String.class,
			com.wonday.farm.change.dto.WorkCancellationRequest.class);
		var mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
		assertThat(mapping.value()).containsExactly("/{assignmentId}/cancellation");
		assertThat(mapping.consumes()).isEmpty();
		var base = WorkCancellationApi.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
		assertThat(base.value()).containsExactly("/api/work-assignments");
		assertThat(WorkCancellationErrorHandler.class.getAnnotation(org.springframework.web.bind.annotation.RestControllerAdvice.class)
			.assignableTypes()).containsExactly(WorkCancellationApi.class);
	}
}

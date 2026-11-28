package com.wonday.farm.change.api;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.change;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.Type;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkEarlyEndErrorHandler;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.change.service.WorkEarlyEndService;
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

// standalone MockMvc: 실제 WorkEarlyEndApi + 실제 오류 처리기 + 실제 서비스, 기록기만 mock.
// 실제 Security 필터 체인·세션 쿠키·CSRF·hasRole·익명 401·Boot /error는 통과하지 않는다(미검증).
@DisplayName("CHANGE-03 조기 종료 API (standalone MockMvc)")
class WorkEarlyEndApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PATH = "/api/work-assignments/31/early-end";
	private static final String BODY = "{\"actualEndAt\":\"2026-11-30T14:00:00.123456+09:00\",\"reasonCode\":\"OTHER\"}";
	private static final String SECRET = "SQL [insert into work_change] jdbc:mysql://10.0.0.5 password=s3cr3t 01012345678";
	private final WorkChangeRecorder recorder = mock(WorkChangeRecorder.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkEarlyEndApi(new WorkEarlyEndService(recorder)))
		.setControllerAdvice(new WorkEarlyEndErrorHandler())
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
	private MockHttpServletResponse send(String path, String body) throws Exception { return call(post(path).contentType("application/json").content(body)); }
	private static WorkChange stored(Reason reason, WeatherReason weather, String detail, long version) {
		var change = change(77L, 31L, Type.EARLY_ENDED, reason, weather, detail,
			LocalDateTime.parse("2026-11-30T05:00:00.123456"), LocalDateTime.parse("2026-11-30T09:00:00.654321"));
		org.springframework.test.util.ReflectionTestUtils.setField(change, "sourceVersion", version);
		return change;
	}
	private void returns(WorkChange change, String zone) {
		given(recorder.endWork(anyLong(), anyLong(), any(), any(), any(), any())).willReturn(new WorkChangeRecorder.RecordedEarlyEnd(change, zone));
	}
	private void fails(RuntimeException failure) {
		willThrow(failure).given(recorder).endWork(anyLong(), anyLong(), any(), any(), any(), any());
	}
	private static String error(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentType()).startsWith("application/json");
		var text = response.getContentAsString(StandardCharsets.UTF_8);
		var json = JSON.readTree(text);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(text).doesNotContain("s3cr3t", "jdbc:", "insert into", "01012345678", "Exception", "SECRET-INPUT", "<script>", "1234567");
		return json.get("message").asString();
	}

	@Test
	@DisplayName("성공 200: 세션 회원 ID 전달, 응답 필드·enum·문자열 버전, effectiveAt≠recordedAt, +09:00, no-store")
	void success() throws Exception {
		returns(stored(Reason.WEATHER, WeatherReason.RAIN, "강수로 작업을 중단했습니다.", 1), "GMT+9");
		var response = send(PATH, "{\"actualEndAt\":\"2026-11-30T14:00:00.123456+09:00\",\"reasonCode\":\"WEATHER\","
			+ "\"weatherReasonCode\":\"RAIN\",\"reasonDetail\":\"  강수로 작업을 중단했습니다.  \"}");
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("assignmentId", "changeId", "changeType", "reasonCode",
			"weatherReasonCode", "reasonDetail", "effectiveAt", "recordedAt", "updatedAt", "sourceVersion");
		assertThat(json.get("assignmentId").asLong()).isEqualTo(31L);
		assertThat(json.get("changeId").asLong()).isEqualTo(77L);
		assertThat(json.get("changeType").asString()).isEqualTo("EARLY_ENDED");
		assertThat(json.get("reasonCode").asString()).isEqualTo("WEATHER");
		assertThat(json.get("weatherReasonCode").asString()).isEqualTo("RAIN");
		assertThat(json.get("reasonDetail").asString()).isEqualTo("강수로 작업을 중단했습니다.");
		assertThat(json.get("sourceVersion").isString()).isTrue();
		assertThat(json.get("sourceVersion").asString()).isEqualTo("1");
		assertThat(json.get("effectiveAt").asString()).isEqualTo("2026-11-30T14:00:00.123456+09:00");
		assertThat(json.get("recordedAt").asString()).isEqualTo("2026-11-30T18:00:00.654321+09:00");
		assertThat(json.get("updatedAt").asString()).isEqualTo("2026-11-30T18:00:00.654321+09:00");
		verify(recorder).endWork(10L, 31L, OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00"), Reason.WEATHER, WeatherReason.RAIN,
			"강수로 작업을 중단했습니다.");
	}

	@Test
	@DisplayName("성공 200: null 사유·설명, Z 입력도 같은 Instant로 기록기에 전달")
	void nullsAndZ() throws Exception {
		returns(stored(Reason.OTHER, null, null, 4), "GMT+9");
		var response = send(PATH, "{\"actualEndAt\":\"2026-11-30T05:00:00.123456Z\",\"reasonCode\":\"OTHER\",\"reasonDetail\":\"  \"}");
		assertThat(response.getStatus()).isEqualTo(200);
		var json = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
		assertThat(json.get("weatherReasonCode").isNull()).isTrue();
		assertThat(json.get("reasonDetail").isNull()).isTrue();
		assertThat(json.get("sourceVersion").asString()).isEqualTo("4");
		var captor = org.mockito.ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(recorder).endWork(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(31L), captor.capture(),
			org.mockito.ArgumentMatchers.eq(Reason.OTHER), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
		assertThat(captor.getValue().toInstant()).isEqualTo(OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00").toInstant());
	}

	@Test
	@DisplayName("본문의 처리자·저장 시각·변경 ID는 사용하지 않음")
	void clientCannotChooseActorOrRecordedAt() throws Exception {
		returns(stored(Reason.OTHER, null, null, 1), "GMT+9");
		var response = send(PATH, "{\"actualEndAt\":\"2026-11-30T14:00:00.123456+09:00\",\"reasonCode\":\"OTHER\",\"employerId\":999,"
			+ "\"actorId\":999,\"recordedAt\":\"2020-01-01T00:00:00Z\",\"updatedAt\":\"2020-01-01T00:00:00Z\",\"changeId\":1}");
		assertThat(response.getStatus()).isEqualTo(200);
		verify(recorder).endWork(10L, 31L, OffsetDateTime.parse("2026-11-30T14:00:00.123456+09:00"), Reason.OTHER, null, null);
		assertThat(JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("recordedAt").asString()).startsWith("2026-11-30T18:00");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "+1", "1.5", "1e3", "abc", "01", "9223372036854775808"})
	@DisplayName("잘못된 경로 ID 400")
	void invalidPathId(String id) throws Exception {
		error(send("/api/work-assignments/" + id + "/early-end", BODY), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{", "null", "[]", "{}", "{\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":null,\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00\",\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00.1234567+09:00\",\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00.1234560+09:00\",\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-02-30T14:00:00+09:00\",\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":1764651600,\"reasonCode\":\"OTHER\"}", "{\"actualEndAt\":true,\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":[2026,11,30],\"reasonCode\":\"OTHER\"}", "{\"actualEndAt\":{},\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\"}", "{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":1}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"WEATHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"OTHER\",\"weatherReasonCode\":\"RAIN\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"OTHER\",\"reasonDetail\":true}",
		"{\"actualEndAt\":\"<script>SECRET-INPUT</script>\",\"reasonCode\":\"OTHER\"}",
		"{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"<script>SECRET-INPUT</script>\"}"})
	@DisplayName("본문·시각 형식·자동 변환·사유 조합 오류 400 (원문 비노출)")
	void invalidBody(String body) throws Exception {
		error(send(PATH, body), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("설명 501 코드 포인트 400, 500은 기록기 전달")
	void detailLength() throws Exception {
		returns(stored(Reason.OTHER, null, "😀".repeat(500), 1), "GMT+9");
		assertThat(send(PATH, "{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"OTHER\",\"reasonDetail\":\"" + "😀".repeat(500) + "\"}")
			.getStatus()).isEqualTo(200);
		assertThat(error(send(PATH, "{\"actualEndAt\":\"2026-11-30T14:00:00+09:00\",\"reasonCode\":\"OTHER\",\"reasonDetail\":\"" + "가".repeat(501) + "\"}"),
			400, "INVALID_REQUEST")).contains("500자");
	}

	@Test
	@DisplayName("실제 종료 범위 오류 400 메시지")
	void timeRange() throws Exception {
		fails(WorkEarlyEndException.invalidTime());
		assertThat(error(send(PATH, BODY), 400, "INVALID_REQUEST")).contains("약정 시작 이상·종료 미만");
	}

	@Test
	@DisplayName("다른 소유자·없음 404, 역할·principal 없음 403")
	void notFoundAndForbidden() throws Exception {
		fails(WorkEarlyEndException.notFound());
		error(send(PATH, BODY), 404, "WORK_ASSIGNMENT_NOT_FOUND");
		fails(new AccessDeniedException(SECRET));
		error(send(PATH, BODY), 403, "EMPLOYER_ROLE_REQUIRED");
		SecurityContextHolder.clearContext();
		error(send(PATH, BODY), 403, "EMPLOYER_ROLE_REQUIRED");
	}

	@ParameterizedTest
	@ValueSource(strings = {"WORK_NOT_STARTED", "WORK_ALREADY_CANCELLED", "WORK_ALREADY_COMPLETED", "EARLY_END_CONTENT_CONFLICT"})
	@DisplayName("상태 409 코드와 메시지")
	void conflicts(String code) throws Exception {
		fails(WorkEarlyEndException.conflict(code, "메시지-" + code));
		assertThat(error(send(PATH, BODY), 409, code)).isEqualTo("메시지-" + code);
	}

	@Test
	@DisplayName("입력 기한 초과 409와 문의 안내, DB 무결성 409")
	void deadlineAndIntegrity() throws Exception {
		fails(WorkEarlyEndException.deadlinePassed());
		assertThat(error(send(PATH, BODY), 409, "EARLY_END_DEADLINE_PASSED")).contains("담당자에게 문의");
		fails(new DataIntegrityViolationException(SECRET));
		error(send(PATH, BODY), 409, "WORK_STATE_CONFLICT");
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded", "application/xml", "application/octet-stream"})
	@DisplayName("지원하지 않는 Content-Type 415 INVALID_REQUEST JSON")
	void mediaType(String type) throws Exception {
		error(call(post(PATH).contentType(type).content("actualEndAt=x")), 415, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
	}

	@Test
	@DisplayName("503·500, 내부 메시지 비노출")
	void unavailableAndUnexpected() throws Exception {
		for (RuntimeException failure : new RuntimeException[] {InsuranceApiException.unavailable(), WorkEarlyEndException.unavailable(),
				new DataAccessResourceFailureException(SECRET), new QueryTimeoutException(SECRET), new CannotCreateTransactionException(SECRET)}) {
			fails(failure);
			error(send(PATH, BODY), 503, "SERVICE_UNAVAILABLE");
		}
		fails(new IllegalStateException(SECRET));
		error(send(PATH, BODY), 500, "INTERNAL_SERVER_ERROR");
	}

	@Test
	@DisplayName("GET 405·본문 없음 400, 선언(consumes 없음·assignableTypes)")
	void declarations() throws Exception {
		assertThat(call(get(PATH)).getStatus()).isEqualTo(405);
		error(call(post(PATH).contentType("application/json")), 400, "INVALID_REQUEST");
		verifyNoInteractions(recorder);
		var mapping = WorkEarlyEndApi.class.getMethod("end", FarmPrincipal.class, String.class, WorkEarlyEndRequest.class)
			.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
		assertThat(mapping.value()).containsExactly("/{assignmentId}/early-end");
		assertThat(mapping.consumes()).isEmpty();
		assertThat(WorkEarlyEndApi.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value())
			.containsExactly("/api/work-assignments");
		assertThat(WorkEarlyEndErrorHandler.class.getAnnotation(org.springframework.web.bind.annotation.RestControllerAdvice.class).assignableTypes())
			.containsExactly(WorkEarlyEndApi.class);
	}
}

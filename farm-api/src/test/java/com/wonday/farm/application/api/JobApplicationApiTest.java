package com.wonday.farm.application.api;

import static com.wonday.farm.application.testsupport.ApplicationTestData.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.wonday.farm.application.dto.ApplyJobPostingRequest;
import com.wonday.farm.application.dto.ApplyJobPostingResponse;
import com.wonday.farm.application.exception.ApplicationErrorHandler;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.application.service.JobApplicationService;
import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 JobApplicationApi + ApplicationErrorHandler + 기본 메시지 변환기·Bean Validation.
// principal은 SecurityContext에 직접 넣는다. 실제 Security 체인(익명 401, hasRole("WORKER") 403 JSON 핸들러, CSRF 차단,
// 세션 격리)과 Spring Boot JsonMapper 설정은 이 테스트에 없다 (통합 검증 대상).
@DisplayName("APP-02 지원 API (standalone MockMvc, principal 직접 주입)")
class JobApplicationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String URL = "/api/job-postings/101/applications";
	private static final String BODY = "{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1},{\"workScheduleId\":202,\"termsVersion\":3}]}";
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db password=s3cr3t worker@example.com 010-1234-5678";
	private static final ApplyJobPostingResponse RESPONSE = new ApplyJobPostingResponse(101, List.of(
		new ApplyJobPostingResponse.Application(9001, 201, 1, "PENDING", 1, OffsetDateTime.parse("2026-12-01T10:02:03.456789+09:00")),
		new ApplyJobPostingResponse.Application(9002, 202, 2, "PENDING", 3, OffsetDateTime.parse("2026-12-01T10:02:03.456789+09:00"))));

	private final JobApplicationService service = mock(JobApplicationService.class);
	private LocalValidatorFactoryBean validator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new JobApplicationApi(service)).setControllerAdvice(new ApplicationErrorHandler())
			.setValidator(validator).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(5L);
		given(principal.role()).willReturn(MemberRole.WORKER);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(service.apply(anyLong(), anyLong(), anyString(), any())).willReturn(new JobApplicationService.Result(false, RESPONSE));
	}

	@AfterEach
	void tearDown() {
		validator.close();
		SecurityContextHolder.clearContext();
	}

	private MockHttpServletRequestBuilder apply(String body) {
		return post(URL).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", KEY).content(body);
	}

	@Nested
	@DisplayName("성공")
	class Success {
		@Test
		@DisplayName("신규 201: principal ID·경로 공고 ID·헤더 키·일정을 전달, 응답 필드와 +09:00 마이크로초, no-store")
		void created() throws Exception {
			var response = perform(apply(BODY));

			assertThat(response.getStatus()).isEqualTo(201);
			assertNoStore(response);
			var captor = ArgumentCaptor.forClass(ApplyJobPostingRequest.class);
			verify(service).apply(eq(5L), eq(101L), eq(KEY), captor.capture());
			assertThat(captor.getValue().schedules()).containsExactly(new ApplyJobPostingRequest.Schedule(201L, 1),
				new ApplyJobPostingRequest.Schedule(202L, 3));
			JsonNode body = parse(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("jobPostingId", "applications");
			JsonNode first = body.get("applications").get(0);
			assertThat(first.propertyNames()).containsExactlyInAnyOrder("applicationId", "workScheduleId", "applicationRound",
				"status", "appliedTermsVersion", "appliedAt");
			assertThat(first.get("appliedAt").asString()).isEqualTo("2026-12-01T10:02:03.456789+09:00");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("workerId", "\"5\"", "csrf", "token");
		}

		@Test
		@DisplayName("재요청 200: 서비스가 replayed=true면 같은 본문을 200으로")
		void replayed() throws Exception {
			given(service.apply(anyLong(), anyLong(), anyString(), any())).willReturn(new JobApplicationService.Result(true, RESPONSE));

			var response = perform(apply(BODY));

			assertThat(response.getStatus()).isEqualTo(200);
			assertNoStore(response);
			assertThat(parse(response).get("applications").get(1).get("applicationRound").asInt()).isEqualTo(2);
		}

		@Test
		@DisplayName("쿼리·헤더로 workerId/employerId를 보내도 principal ID(5)만 사용")
		void ownerFromPrincipalOnly() throws Exception {
			perform(apply(BODY).param("workerId", "99").param("employerId", "99").header("X-Worker-Id", "99"));

			verify(service).apply(eq(5L), eq(101L), eq(KEY), any());
		}

		@Test
		@DisplayName("본문에 workerId를 넣어도 소유자가 바뀌지 않는다 (무시되거나 400, 어느 쪽이든 99로 호출 안 함)")
		void bodyOwnerIgnored() throws Exception {
			var response = perform(apply("{\"workerId\":99,\"employerId\":99,\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1}]}"));

			assertThat(response.getStatus()).isIn(201, 400);
			verify(service, org.mockito.Mockito.never()).apply(eq(99L), anyLong(), anyString(), any());
		}

		@Test
		@DisplayName("키 대소문자 정규화는 서비스 책임: 헤더 값은 그대로 전달")
		void keyPassedAsIs() throws Exception {
			perform(post(URL).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", KEY.toUpperCase()).content(BODY));

			verify(service).apply(eq(5L), eq(101L), eq(KEY.toUpperCase()), any());
		}
	}

	@Nested
	@DisplayName("요청 형식 오류")
	class Invalid {
		@Test
		@DisplayName("Idempotency-Key 누락·두 개(헤더 중복)는 400 INVALID_REQUEST이고 서비스 호출 없음")
		void keyHeader() throws Exception {
			assertError(perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)), 400, "INVALID_REQUEST");
			assertError(perform(apply(BODY).header("Idempotency-Key", "6f2b8c1e-9a4d-4c6b-8e2f-1a2b3c4d5e6f")), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1}",
			"",
			"{\"schedules\":[{\"workScheduleId\":\"201\",\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":1.5}]}",
			"{\"schedules\":[{\"workScheduleId\":9223372036854775808,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":2147483648}]}",
			"{\"schedules\":null}",
			"{\"schedules\":[]}",
			"{\"schedules\":[null]}",
			"{\"schedules\":[{\"workScheduleId\":0,\"termsVersion\":1}]}",
			"{\"schedules\":[{\"workScheduleId\":201,\"termsVersion\":0}]}"})
		@DisplayName("깨진 JSON·빈 본문·문자열/소수/범위 초과 정수·null/빈 목록·null 항목·0 값은 400 INVALID_REQUEST")
		void body(String body) throws Exception {
			assertError(perform(apply(body)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("101개 일정은 Bean Validation에서 400")
		void tooMany() throws Exception {
			var items = new StringBuilder();
			for (int i = 1; i <= 101; i++) items.append(i == 1 ? "" : ",").append("{\"workScheduleId\":").append(i).append(",\"termsVersion\":1}");
			assertError(perform(apply("{\"schedules\":[" + items + "]}")), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "postingId={0}")
		@ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
		@DisplayName("숫자가 아닌·범위 초과 공고 ID는 400")
		void postingId(String id) throws Exception {
			assertError(perform(post("/api/job-postings/" + id + "/applications").contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", KEY).content(BODY)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("application/json이 아닌 요청은 415이고 서비스 호출 없음")
		void unsupportedMediaType() throws Exception {
			var response = perform(post(URL).contentType(MediaType.TEXT_PLAIN).header("Idempotency-Key", KEY).content(BODY));

			assertThat(response.getStatus()).isEqualTo(415);
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("[특성화] 415 응답에 오류 처리기의 code·message 본문과 no-store가 붙는지 기록 (핸들러 선택 전 예외)")
		void unsupportedMediaTypeBody() throws Exception {
			var response = perform(post(URL).contentType(MediaType.TEXT_PLAIN).header("Idempotency-Key", KEY).content(BODY));

			assertThat(response.getStatus()).isEqualTo(415);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
			assertThat(response.getHeader("Cache-Control")).isNull();
		}
	}

	@Nested
	@DisplayName("서비스 예외 → 오류 응답")
	class Errors {
		static Stream<Arguments> cases() {
			return Stream.of(
				Arguments.of(ApplicationException.invalidRequest(), 400, "INVALID_REQUEST"),
				Arguments.of(new AccessDeniedException(SECRET), 403, "WORKER_ROLE_REQUIRED"),
				Arguments.of(new ApplicationException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고"), 404, "JOB_POSTING_NOT_FOUND"),
				Arguments.of(new ApplicationException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "일정"), 404, "WORK_SCHEDULE_NOT_FOUND"),
				Arguments.of(ApplicationException.conflict("SCHEDULE_TERMS_CHANGED", "변경"), 409, "SCHEDULE_TERMS_CHANGED"),
				Arguments.of(ApplicationException.conflict("IDEMPOTENCY_KEY_CONFLICT", "키"), 409, "IDEMPOTENCY_KEY_CONFLICT"),
				Arguments.of(ApplicationException.conflict("ALREADY_APPLIED", "중복"), 409, "ALREADY_APPLIED"),
				Arguments.of(new DataIntegrityViolationException(SECRET), 409, "APPLICATION_CONFLICT"),
				Arguments.of(ApplicationException.unavailable(), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new DataAccessResourceFailureException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotAcquireLockException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new QueryTimeoutException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotCreateTransactionException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new TransactionTimedOutException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new IllegalStateException(SECRET), 500, "INTERNAL_SERVER_ERROR"),
				Arguments.of(new RuntimeException(SECRET, new IllegalArgumentException(SECRET)), 500, "INTERNAL_SERVER_ERROR"));
		}

		@ParameterizedTest(name = "{0} → {1} {2}")
		@MethodSource("cases")
		@DisplayName("상태코드·코드 매핑, 본문은 code·message뿐, no-store, 예외 상세·개인정보 미노출")
		void maps(RuntimeException exception, int status, String code) throws Exception {
			given(service.apply(anyLong(), anyLong(), anyString(), any())).willThrow(exception);

			var response = perform(apply(BODY));

			assertError(response, status, code);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8))
				.doesNotContain("jdbc", "s3cr3t", "example.com", "010-1234", "Exception", "trace");
		}

		@Test
		@DisplayName("principal 없음(standalone)은 403이고 서비스 호출 없음 — 실제 체인의 익명 401과는 별개")
		void noPrincipal() throws Exception {
			SecurityContextHolder.clearContext();

			assertError(perform(apply(BODY)), 403, "WORKER_ROLE_REQUIRED");
			verifyNoInteractions(service);
		}
	}

	private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
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
	}
}

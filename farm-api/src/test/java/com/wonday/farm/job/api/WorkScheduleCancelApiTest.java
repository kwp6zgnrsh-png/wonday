package com.wonday.farm.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.CancelWorkScheduleRequest;
import com.wonday.farm.job.dto.CancelledWorkScheduleResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkScheduleCancelErrorHandler;
import com.wonday.farm.job.exception.WorkScheduleCancelException;
import com.wonday.farm.job.service.WorkScheduleCancelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

// standalone MockMvc: 컨트롤러 + WorkScheduleCancelErrorHandler + 기본 메시지 변환기 + @AuthenticationPrincipal 해석기.
// principal을 SecurityContext에 직접 넣으므로 실제 Security 필터·세션·CSRF·익명 401·SecurityConfig의 역할 차단은
// 검증하지 않는다 (통합 검증 대상).
class WorkScheduleCancelApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String URL = "/api/job-postings/101/schedules/201/cancel";
	private static final String BODY = "{\"expectedTermsVersion\":1}";

	private final WorkScheduleCancelService service = mock(WorkScheduleCancelService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkScheduleCancelApi(service))
		.setControllerAdvice(new WorkScheduleCancelErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
		.build();

	@BeforeEach
	void signedInEmployer() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(42L);
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	class Success {
		@Test
		void passesPrincipalIdPathIdsAndBodyAndReturnsUtcCancelTime() throws Exception {
			given(service.cancel(anyLong(), anyLong(), anyLong(), any())).willReturn(new CancelledWorkScheduleResponse(101L,
				201L, LocalDate.parse("2026-12-02"), WorkSchedule.Status.REMOVED, 1,
				OffsetDateTime.parse("2026-11-25T08:00:00.123456Z")));

			var response = perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			verify(service).cancel(eq(42L), eq(101L), eq(201L), eq(new CancelWorkScheduleRequest(1)));
			assertThat(response.getStatus()).isEqualTo(200);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("jobPostingId", "workScheduleId", "workDate", "status",
				"termsVersion", "cancelledAt");
			assertThat(body.get("jobPostingId").asLong()).isEqualTo(101L);
			assertThat(body.get("workScheduleId").asLong()).isEqualTo(201L);
			assertThat(body.get("workDate").asString()).isEqualTo("2026-12-02");
			assertThat(body.get("status").asString()).isEqualTo("REMOVED");
			assertThat(body.get("termsVersion").asInt()).isEqualTo(1);
			assertThat(body.get("cancelledAt").asString()).isEqualTo("2026-11-25T08:00:00.123456Z");
		}

		@Test
		void existingResultWithoutCancelTimeIsJsonNull() throws Exception {
			given(service.cancel(anyLong(), anyLong(), anyLong(), any())).willReturn(new CancelledWorkScheduleResponse(101L,
				201L, LocalDate.parse("2026-12-02"), WorkSchedule.Status.REMOVED, 3, null));

			JsonNode body = json(perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)));

			assertThat(body.has("cancelledAt")).isTrue();
			assertThat(body.get("cancelledAt").isNull()).isTrue();
		}
	}

	@Nested
	class RequestErrors {
		@ParameterizedTest(name = "body={0}")
		@ValueSource(strings = {"{}", "{\"expectedTermsVersion\":null}", "{\"expectedTermsVersion\":0}",
			"{\"expectedTermsVersion\":-1}"})
		void missingOrNonPositiveVersionIsInvalidWorkSchedule(String body) throws Exception {
			assertError(perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)), 400,
				"INVALID_WORK_SCHEDULE", "expectedTermsVersion");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "body={0}")
		@ValueSource(strings = {"", "{", "[]", "null", "{\"expectedTermsVersion\":1.5}", "{\"expectedTermsVersion\":1e0}",
			"{\"expectedTermsVersion\":\"1\"}", "{\"expectedTermsVersion\":[1]}", "{\"expectedTermsVersion\":{}}",
			"{\"expectedTermsVersion\":true}", "{\"expectedTermsVersion\":2147483648}"})
		void unreadableOrNonIntegerBodyIsInvalidRequestBody(String body) throws Exception {
			assertError(perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)), 400,
				"INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}

		@Test
		void nonJsonOrMissingContentTypeIsInvalidRequestBody() throws Exception {
			assertError(perform(post(URL).contentType(MediaType.TEXT_PLAIN).content(BODY)), 400, "INVALID_REQUEST_BODY", null);
			assertError(perform(post(URL).content(BODY)), 400, "INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "path={0}")
		@ValueSource(strings = {"/api/job-postings/abc/schedules/201/cancel", "/api/job-postings/101/schedules/2.5/cancel",
			"/api/job-postings/99999999999999999999/schedules/201/cancel"})
		void nonNumericPathIdIsInvalidRequestBody(String path) throws Exception {
			assertError(perform(post(path).contentType(MediaType.APPLICATION_JSON).content(BODY)), 400,
				"INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}
	}

	@Nested
	class ServiceErrors {
		static Stream<Arguments> domainErrors() {
			return Stream.of(
				Arguments.of(new WorkScheduleCancelException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "ID", null), 400, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고", null), 404, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "일정", null), 404, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT", "버전",
					"expectedTermsVersion"), 409, "expectedTermsVersion"),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "SCHEDULE_NOT_CANCELLABLE", "상태", null), 409, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "SCHEDULE_ALREADY_CONFIRMED", "확정", null), 409, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "SCHEDULE_ALREADY_STARTED", "시작", null), 409, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "TIME_ZONE_UNAVAILABLE", "시간대", null), 409, null),
				Arguments.of(new WorkScheduleCancelException(HttpStatus.CONFLICT, "SCHEDULE_START_TIME_UNAVAILABLE", "시각", null),
					409, null));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("domainErrors")
		void domainErrorKeepsStatusCodeMessageAndField(WorkScheduleCancelException error, int status, String field)
				throws Exception {
			given(service.cancel(anyLong(), anyLong(), anyLong(), any())).willThrow(error);

			var response = perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			assertError(response, status, error.code(), field);
			assertThat(json(response).get("message").asString()).isEqualTo(error.getMessage());
		}

		@Test
		void accessDeniedIsEmployerRoleRequired() throws Exception {
			given(service.cancel(anyLong(), anyLong(), anyLong(), any())).willThrow(new AccessDeniedException("Employer role required"));

			var response = perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			assertError(response, 403, "EMPLOYER_ROLE_REQUIRED", null);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Employer role required");
		}

		static Stream<RuntimeException> storageConflicts() {
			return Stream.of(
				new DataIntegrityViolationException("Cannot add or update a child row; SQL [update work_schedule set status=?]"),
				new PessimisticLockingFailureException("Deadlock found when trying to get lock; SQL [select ... for update]"),
				new CannotAcquireLockException("Lock wait timeout exceeded; SQL [select ... for update]"));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("storageConflicts")
		void storageConflictIsScheduleCancelConflictWithoutSqlDetails(RuntimeException error) throws Exception {
			given(service.cancel(anyLong(), anyLong(), anyLong(), any())).willThrow(error);

			var response = perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			assertError(response, 409, "SCHEDULE_CANCEL_CONFLICT", null);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SQL").doesNotContain("Deadlock")
				.doesNotContain("Lock wait").doesNotContain("work_schedule").doesNotContain("child row");
		}
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static JsonNode json(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertError(MockHttpServletResponse response, int status, String code, String field) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		JsonNode body = json(response);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message", "field");
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("message").asString()).isNotBlank();
		if (field == null) {
			assertThat(body.get("field").isNull()).isTrue();
		} else {
			assertThat(body.get("field").asString()).isEqualTo(field);
		}
	}
}

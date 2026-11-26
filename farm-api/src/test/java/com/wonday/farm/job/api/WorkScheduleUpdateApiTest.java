package com.wonday.farm.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.UpdateWorkScheduleRequest;
import com.wonday.farm.job.dto.UpdatedWorkScheduleResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.exception.WorkScheduleUpdateErrorHandler;
import com.wonday.farm.job.exception.WorkScheduleUpdateException;
import com.wonday.farm.job.service.WorkScheduleUpdateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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

// standalone MockMvc: 컨트롤러 + WorkScheduleUpdateErrorHandler + 기본 메시지 변환기 + @AuthenticationPrincipal 해석기.
// SecurityContext에 principal을 직접 넣으므로 실제 Security 필터 체인·세션·CSRF·EMPLOYER 경로 권한(SecurityConfig)은
// 검증하지 않는다 (통합 검증 대상). 브라우저 데모 화면의 JavaScript도 대상이 아니다.
class WorkScheduleUpdateApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String URL = "/api/job-postings/1/schedules/11";
	private static final String BODY = """
		{"expectedTermsVersion":1,"workDate":"2026-12-02","plannedStartAt":"2026-12-02T09:00:00",
		 "plannedEndAt":"2026-12-02T17:00:00","breakStartAt":"2026-12-02T12:00:00","breakEndAt":"2026-12-02T13:00:00"}""";

	private final WorkScheduleUpdateService service = mock(WorkScheduleUpdateService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkScheduleUpdateApi(service))
		.setControllerAdvice(new WorkScheduleUpdateErrorHandler())
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
	class Delegation {
		@Test
		void passesPrincipalIdPathIdsAndBodyToServiceAndReturnsItsResult() throws Exception {
			given(service.update(anyLong(), anyLong(), anyLong(), any())).willReturn(new UpdatedWorkScheduleResponse(1L, 11L,
				LocalDate.parse("2026-12-02"), LocalDateTime.parse("2026-12-02T09:00"), LocalDateTime.parse("2026-12-02T17:00"),
				null, null, WorkSchedule.Status.PAUSED, 2));

			var response = perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			var request = ArgumentCaptor.forClass(UpdateWorkScheduleRequest.class);
			verify(service).update(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(11L), request.capture());
			assertThat(request.getValue()).isEqualTo(new UpdateWorkScheduleRequest(1, LocalDate.parse("2026-12-02"),
				LocalDateTime.parse("2026-12-02T09:00"), LocalDateTime.parse("2026-12-02T17:00"),
				LocalDateTime.parse("2026-12-02T12:00"), LocalDateTime.parse("2026-12-02T13:00")));
			assertThat(response.getStatus()).isEqualTo(200);
			JsonNode body = json(response);
			assertThat(body.get("jobPostingId").asLong()).isEqualTo(1L);
			assertThat(body.get("workScheduleId").asLong()).isEqualTo(11L);
			assertThat(body.get("workDate").asString()).isEqualTo("2026-12-02");
			assertThat(LocalDateTime.parse(body.get("plannedStartAt").asString())).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(body.get("breakStartAt").isNull()).isTrue();
			assertThat(body.get("status").asString()).isEqualTo("PAUSED");
			assertThat(body.get("termsVersion").asInt()).isEqualTo(2);
		}
	}

	@Nested
	class RequestErrors {
		@Test
		void beanValidationErrorIsInvalidWorkScheduleWithField() throws Exception {
			var response = perform(put(URL).contentType(MediaType.APPLICATION_JSON)
				.content(BODY.replace("\"expectedTermsVersion\":1", "\"expectedTermsVersion\":0")));

			assertError(response, 400, "INVALID_WORK_SCHEDULE", "expectedTermsVersion");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "body={0}")
		@ValueSource(strings = {"", "{", "[]", "{\"expectedTermsVersion\":\"1\"}", "{\"workDate\":[2026,12,2]}",
			"{\"plannedStartAt\":\"2026-12-02T09:00Z\"}"})
		void unreadableBodyIsInvalidRequestBodyWithoutField(String body) throws Exception {
			assertError(perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(body)), 400, "INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "path={0}")
		@ValueSource(strings = {"/api/job-postings/abc/schedules/11", "/api/job-postings/1/schedules/1.5"})
		void nonNumericPathIdIsInvalidRequestBody(String path) throws Exception {
			assertError(perform(put(path).contentType(MediaType.APPLICATION_JSON).content(BODY)), 400, "INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}

		@Test
		void nonJsonContentTypeIsInvalidRequestBody() throws Exception {
			assertError(perform(put(URL).contentType(MediaType.TEXT_PLAIN).content(BODY)), 400, "INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}

		@Test
		void missingContentTypeIsInvalidRequestBody() throws Exception {
			assertError(perform(put(URL).content(BODY)), 400, "INVALID_REQUEST_BODY", null);
			verifyNoInteractions(service);
		}
	}

	@Nested
	class ServiceErrors {
		static Stream<Arguments> mapped() {
			return Stream.of(
				Arguments.of(new JobPostingException("INVALID_REQUEST", "공고 ID와 일정 ID는 양수여야 합니다.", null), 400, "INVALID_REQUEST", null),
				Arguments.of(new JobPostingException("DUPLICATE_WORK_DATE", "중복", "workDate"), 400, "DUPLICATE_WORK_DATE", "workDate"),
				Arguments.of(new JobPostingException("INVALID_BREAK_TIME", "휴게", "breakEndAt"), 400, "INVALID_BREAK_TIME", "breakEndAt"),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고 없음", null),
					404, "JOB_POSTING_NOT_FOUND", null),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.NOT_FOUND, "WORK_SCHEDULE_NOT_FOUND", "일정 없음", null),
					404, "WORK_SCHEDULE_NOT_FOUND", null),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.CONFLICT, "SCHEDULE_VERSION_CONFLICT", "버전",
					"expectedTermsVersion"), 409, "SCHEDULE_VERSION_CONFLICT", "expectedTermsVersion"),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.CONFLICT, "SCHEDULE_NOT_EDITABLE", "상태", null),
					409, "SCHEDULE_NOT_EDITABLE", null),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.CONFLICT, "SCHEDULE_ALREADY_CONFIRMED", "확정", null),
					409, "SCHEDULE_ALREADY_CONFIRMED", null),
				Arguments.of(new WorkScheduleUpdateException(HttpStatus.CONFLICT, "TIME_ZONE_UNAVAILABLE", "시간대", null),
					409, "TIME_ZONE_UNAVAILABLE", null),
				Arguments.of(new AccessDeniedException("Employer role required"), 403, "EMPLOYER_ROLE_REQUIRED", null));
		}

		@ParameterizedTest(name = "{2}")
		@MethodSource("mapped")
		void mapsStatusCodeMessageAndField(RuntimeException error, int status, String code, String field) throws Exception {
			given(service.update(anyLong(), anyLong(), anyLong(), any())).willThrow(error);

			var response = perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			assertError(response, status, code, field);
			if (!(error instanceof AccessDeniedException)) {
				assertThat(json(response).get("message").asString()).isEqualTo(error.getMessage());
			}
		}

		static Stream<RuntimeException> concurrent() {
			return Stream.of(
				new DataIntegrityViolationException("Duplicate entry '1-2026-12-02' for key 'uq_posting_work_date' SQL [update work_schedule]"),
				new PessimisticLockingFailureException("Deadlock found when trying to get lock; SQL [select ... for update]"),
				new CannotAcquireLockException("Lock wait timeout exceeded; SQL [select ... for update]"));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("concurrent")
		void storageConflictIs409ScheduleUpdateConflictWithoutSqlDetails(RuntimeException error) throws Exception {
			given(service.update(anyLong(), anyLong(), anyLong(), any())).willThrow(error);

			var response = perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(BODY));

			assertError(response, 409, "SCHEDULE_UPDATE_CONFLICT", null);
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SQL").doesNotContain("Duplicate")
				.doesNotContain("Deadlock").doesNotContain("uq_posting_work_date").doesNotContain("Lock wait");
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

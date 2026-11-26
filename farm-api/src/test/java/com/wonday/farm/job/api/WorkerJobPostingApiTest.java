package com.wonday.farm.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.WorkerJobPostingResponse;
import com.wonday.farm.job.dto.WorkerJobPostingsResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkerJobPostingErrorHandler;
import com.wonday.farm.job.exception.WorkerJobPostingException;
import com.wonday.farm.job.service.WorkerJobPostingQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
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

// standalone MockMvc: 실제 WorkerJobPostingApi + WorkerJobPostingErrorHandler + 기본 메시지 변환기 + principal 해석기.
// principal을 SecurityContext에 직접 넣으므로 실제 인증이 아니다. Security 필터·세션·CSRF·SecurityConfig의 역할 제한,
// 다른 컨트롤러(MyJobPostingApi의 /mine)와의 실제 매핑 우선순위는 검증하지 않는다 (통합 검증 대상).
@DisplayName("APP-01 근로자 공고 API 컨트롤러·오류·JSON")
class WorkerJobPostingApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final WorkerJobPostingQueryService service = mock(WorkerJobPostingQueryService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkerJobPostingApi(service))
		.setControllerAdvice(new WorkerJobPostingErrorHandler())
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
		.build();

	@BeforeEach
	void signedInWorker() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(5L);
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(service.list(anyLong(), anyInt(), anyInt())).willReturn(new WorkerJobPostingsResponse(List.of(), 0, 20, false));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("요청 전달")
	class Delegation {
		@Test
		@DisplayName("page·size를 생략하면 principal 회원 ID와 기본값 0·20을 전달한다")
		void defaults() throws Exception {
			perform(get("/api/job-postings"));

			verify(service).list(5L, 0, 20);
		}

		@Test
		@DisplayName("명시한 page·size를 전달한다")
		void explicitPaging() throws Exception {
			perform(get("/api/job-postings").param("page", "3").param("size", "100"));

			verify(service).list(5L, 3, 100);
		}

		@Test
		@DisplayName("쿼리의 workerId·memberId 같은 값은 principal ID를 대신하지 않는다")
		void queryCannotReplacePrincipal() throws Exception {
			given(service.detail(anyLong(), anyLong())).willReturn(detail(List.of()));

			perform(get("/api/job-postings").param("workerId", "99").param("memberId", "99"));
			perform(get("/api/job-postings/1").param("workerId", "99"));

			verify(service).list(5L, 0, 20);
			verify(service).detail(5L, 1L);
		}

		@Test
		@DisplayName("음수 page·size도 형식이 정수면 그대로 서비스에 넘겨 서비스가 INVALID_PAGINATION으로 판정한다")
		void negativeValuesReachService() throws Exception {
			given(service.list(5L, -1, 20)).willThrow(new WorkerJobPostingException(HttpStatus.BAD_REQUEST,
				"INVALID_PAGINATION", "page는 0 이상, size는 1~100이어야 합니다.", "page"));

			assertError(perform(get("/api/job-postings").param("page", "-1")), 400, "INVALID_PAGINATION", "page");
		}
	}

	@Nested
	@DisplayName("정상 JSON")
	class Json {
		@Test
		@DisplayName("목록 JSON: 숫자·boolean·날짜 배열·null 사유·hasNext 형태")
		void listJson() throws Exception {
			given(service.list(5L, 0, 20)).willReturn(new WorkerJobPostingsResponse(List.of(new WorkerJobPostingsResponse.Posting(
				101L, "배추 수확", "해남 예시농장", "VC02100100100102", "노지가을배추", "0058", "수확", 3, 120000L,
				List.of(LocalDate.parse("2026-12-02")), List.of(
					new WorkerJobPostingsResponse.RecruitingSchedule(201L, LocalDate.parse("2026-12-02"), 3L, 0L, false, "CAPACITY_FULL"),
					new WorkerJobPostingsResponse.RecruitingSchedule(202L, LocalDate.parse("2026-12-03"), 1L, 2L, true, null)))),
				0, 20, true));

			var response = perform(get("/api/job-postings"));

			assertThat(response.getStatus()).isEqualTo(200);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("jobPostings", "page", "size", "hasNext");
			assertThat(body.get("hasNext").isBoolean()).isTrue();
			assertThat(body.get("hasNext").asBoolean()).isTrue();
			JsonNode posting = body.get("jobPostings").get(0);
			assertThat(posting.get("cropCode").asString()).isEqualTo("VC02100100100102");
			assertThat(posting.get("workTypeCode").asString()).as("선행 0 보존").isEqualTo("0058");
			assertThat(posting.get("headcount").isInt()).isTrue();
			assertThat(posting.get("dailyWage").isIntegralNumber()).isTrue();
			assertThat(posting.get("dailyWage").asLong()).isEqualTo(120000L);
			assertThat(posting.get("recruitingWorkDates").get(0).asString()).isEqualTo("2026-12-02");
			JsonNode full = posting.get("recruitingSchedules").get(0);
			assertThat(full.get("canApply").asBoolean()).isFalse();
			assertThat(full.get("unavailableReason").asString()).isEqualTo("CAPACITY_FULL");
			assertThat(full.get("remainingSlots").asLong()).isZero();
			JsonNode open = posting.get("recruitingSchedules").get(1);
			assertThat(open.has("unavailableReason")).isTrue();
			assertThat(open.get("unavailableReason").isNull()).isTrue();
		}

		@Test
		@DisplayName("빈 목록은 jobPostings=[]")
		void emptyList() throws Exception {
			JsonNode body = json(perform(get("/api/job-postings")));

			assertThat(body.get("jobPostings").isArray()).isTrue();
			assertThat(body.get("jobPostings").size()).isZero();
			assertThat(body.get("hasNext").asBoolean()).isFalse();
		}

		@Test
		@DisplayName("상세 JSON: 현지 일시(오프셋 없음·마이크로초 보존)·null 휴게·enum 상태·버전·빈 일정 배열")
		void detailJson() throws Exception {
			given(service.detail(5L, 101L)).willReturn(detail(List.of(new WorkerJobPostingResponse.Schedule(201L,
				LocalDate.parse("2026-12-02"), LocalDateTime.parse("2026-12-02T09:00:00.000001"),
				LocalDateTime.parse("2026-12-02T17:00"), null, null, WorkSchedule.Status.REMOVED, 4, 1L, 2L, false,
				"RECRUITMENT_CANCELLED"))));
			given(service.detail(5L, 102L)).willReturn(detail(List.of()));

			JsonNode body = json(perform(get("/api/job-postings/101")));
			JsonNode schedule = body.get("schedules").get(0);

			assertThat(body.get("timeZone").asString()).isEqualTo("GMT+9");
			assertThat(schedule.get("plannedStartAt").asString()).isEqualTo("2026-12-02T09:00:00.000001");
			assertThat(LocalDateTime.parse(schedule.get("plannedEndAt").asString())).isEqualTo(LocalDateTime.parse("2026-12-02T17:00"));
			assertThat(schedule.get("plannedEndAt").asString()).doesNotContain("+").doesNotEndWith("Z");
			assertThat(schedule.get("breakStartAt").isNull()).isTrue();
			assertThat(schedule.get("breakEndAt").isNull()).isTrue();
			assertThat(schedule.get("status").asString()).isEqualTo("REMOVED");
			assertThat(schedule.get("termsVersion").isInt()).isTrue();
			assertThat(schedule.get("termsVersion").asInt()).isEqualTo(4);
			assertThat(schedule.get("canApply").isBoolean()).isTrue();
			assertThat(json(perform(get("/api/job-postings/102"))).get("schedules").size()).isZero();
		}

		@Test
		@DisplayName("응답에 로그인 ID·전화번호·주민번호·고용주 정보 키가 없다")
		void noPersonalKeys() throws Exception {
			given(service.detail(5L, 101L)).willReturn(detail(List.of()));

			String detailBody = perform(get("/api/job-postings/101")).getContentAsString(StandardCharsets.UTF_8);

			for (String key : List.of("loginId", "phone", "residentRegistrationNumber", "employer", "passwordHash", "workerId")) {
				assertThat(detailBody).doesNotContain("\"" + key + "\"");
			}
		}
	}

	@Nested
	@DisplayName("오류 변환")
	class Errors {
		@ParameterizedTest(name = "{0}={1}")
		@CsvSource({"page,abc", "page,1.5", "page,2147483648", "page,-2147483649", "size,x", "size,99999999999"})
		@DisplayName("정수 형식이 아니거나 int 범위를 넘는 page·size는 400 INVALID_REQUEST이고 field는 해당 파라미터다")
		void invalidPaging(String name, String value) throws Exception {
			assertError(perform(get("/api/job-postings").param(name, value)), 400, "INVALID_REQUEST", name);
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("빈 문자열 page·size는 기본값(0·20)으로 처리하는 현재 동작을 확인한다")
		void blankPagingFallsBackToDefaults() throws Exception {
			assertThat(perform(get("/api/job-postings").param("page", "").param("size", "")).getStatus()).isEqualTo(200);
			verify(service).list(5L, 0, 20);
		}

		@ParameterizedTest(name = "id={0}")
		@org.junit.jupiter.params.provider.ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
		@DisplayName("정수가 아니거나 long 범위를 넘는 공고 ID는 400 INVALID_REQUEST, field=jobPostingId")
		void invalidId(String id) throws Exception {
			assertError(perform(get("/api/job-postings/" + id)), 400, "INVALID_REQUEST", "jobPostingId");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("서비스 도메인 예외는 지정한 상태·code·message·field로 변환한다")
		void domainErrors() throws Exception {
			given(service.detail(5L, 0L)).willThrow(new WorkerJobPostingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"공고 ID는 양수여야 합니다.", "jobPostingId"));
			given(service.detail(5L, 999L)).willThrow(new WorkerJobPostingException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND",
				"공고를 찾을 수 없습니다.", null));

			var invalid = perform(get("/api/job-postings/0"));
			var missing = perform(get("/api/job-postings/999"));

			assertError(invalid, 400, "INVALID_REQUEST", "jobPostingId");
			assertThat(json(invalid).get("message").asString()).isEqualTo("공고 ID는 양수여야 합니다.");
			assertError(missing, 404, "JOB_POSTING_NOT_FOUND", null);
			assertThat(json(missing).get("message").asString()).isEqualTo("공고를 찾을 수 없습니다.");
		}

		@Test
		@DisplayName("서비스의 AccessDeniedException은 403 WORKER_ROLE_REQUIRED이며 내부 메시지를 노출하지 않는다")
		void accessDenied() throws Exception {
			given(service.list(anyLong(), anyInt(), anyInt())).willThrow(new AccessDeniedException("Worker role required"));
			given(service.detail(anyLong(), anyLong())).willThrow(new AccessDeniedException("Worker role required"));

			for (var response : List.of(perform(get("/api/job-postings")), perform(get("/api/job-postings/1")))) {
				assertError(response, 403, "WORKER_ROLE_REQUIRED", null);
				assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Worker role required");
			}
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

	private static WorkerJobPostingResponse detail(List<WorkerJobPostingResponse.Schedule> schedules) {
		return new WorkerJobPostingResponse(101L, "배추 수확", "설명", "해남 예시농장", "VC02100100100102", "노지가을배추",
			"0058", "수확", "GMT+9", 3, 120000L, schedules);
	}
}

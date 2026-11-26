package com.wonday.farm.job.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.CreateJobPostingResponse;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.JobPostingErrorHandler;
import com.wonday.farm.job.service.JobPostingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 JobPostingApi + JobPostingErrorHandler + Bean Validator + 기본 메시지 변환기. 서비스는 mock.
// principal은 SecurityContext에 직접 넣는다. 실제 로그인 세션·Security 필터·CSRF·Spring Boot의 ObjectMapper 설정은 검증하지 않는다.
@DisplayName("공고 등록 HTTP 변환의 좌표 처리 (standalone MockMvc)")
class JobLocationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String TEMPLATE = """
		{"title":"사과 수확 근로자 모집","description":"사과 수확과 운반 작업입니다.","cropCode":"TEST-CROP",
		 "workTypeCode":"TEST-WORK","workLocation":"충청북도 충주시 예시농장",%s"timeZone":"GMT+9",
		 "headcount":2,"dailyWage":120000,
		 "schedules":[{"workDate":"2026-11-30","plannedStartAt":"2026-11-30T08:00:00",
		   "plannedEndAt":"2026-11-30T16:00:00","breakStartAt":null,"breakEndAt":null}]}""";

	private final JobPostingService service = mock(JobPostingService.class);
	private MockMvc mvc;
	private LocalValidatorFactoryBean validator;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new JobPostingApi(service))
			.setControllerAdvice(new JobPostingErrorHandler())
			.setValidator(validator)
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(42L);
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(service.create(anyLong(), any())).willReturn(new CreateJobPostingResponse(7L, 42L, 2, List.of(
			new CreateJobPostingResponse.Schedule(8L, LocalDate.parse("2026-11-30"), WorkSchedule.Status.OPEN))));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		validator.close();
	}

	@Nested
	@DisplayName("정상 요청")
	class Accepted {
		@ParameterizedTest(name = "lat={0}, lon={1}")
		@CsvSource({"37.1234565, 127.9876544", "37.12345678901234567890, 127.12345678901234567890",
			"89.9999999, 179.9999999", "-90, -180", "90, 180", "0, 0", "9E1, -1.8E2"})
		@DisplayName("201과 기존 ID 응답, 서비스에는 원본 BigDecimal 그대로(반올림·손실 없음) 세션 회원 ID와 함께 전달")
		void passesOriginalToService(String latitude, String longitude) throws Exception {
			var response = send(latitude, longitude);

			assertThat(response.getStatus()).isEqualTo(201);
			JsonNode body = body(response);
			assertThat(body.get("jobPostingId").asLong()).isEqualTo(7L);
			assertThat(body.get("schedules").get(0).get("workScheduleId").asLong()).isEqualTo(8L);
			ArgumentCaptor<CreateJobPostingRequest> request = ArgumentCaptor.forClass(CreateJobPostingRequest.class);
			verify(service).create(org.mockito.ArgumentMatchers.eq(42L), request.capture());
			assertThat(request.getValue().latitude().toString()).isEqualTo(new java.math.BigDecimal(latitude).toString());
			assertThat(request.getValue().longitude().toString()).isEqualTo(new java.math.BigDecimal(longitude).toString());
		}
	}

	@Nested
	@DisplayName("형식 오류 → 400 INVALID_REQUEST_BODY, 서비스 호출 없음")
	class Malformed {
		@ParameterizedTest(name = "latitude={0}")
		@ValueSource(strings = {"\"37.1\"", "\"\"", "true", "[37.1]", "{}", "NaN", "+37", ".5", "037"})
		@DisplayName("위도 형식 오류")
		void latitude(String raw) throws Exception {
			assertBodyError(send(raw, "127.1"));
		}

		@ParameterizedTest(name = "longitude={0}")
		@ValueSource(strings = {"\"127.1\"", "\"\"", "false", "[]", "{\"v\":1}"})
		@DisplayName("경도 형식 오류")
		void longitude(String raw) throws Exception {
			assertBodyError(send("37.1", raw));
		}

		@Test
		@DisplayName("1000자 초과 숫자, int 범위를 넘는 지수도 500이 아니라 400 INVALID_REQUEST_BODY")
		void extremeNumbers() throws Exception {
			assertBodyError(send("37." + "1".repeat(1000), "127.1"));
			assertBodyError(send("1e9999999999", "127.1"));
			assertBodyError(send("37.1", "-1e9999999999"));
		}

		private void assertBodyError(MockHttpServletResponse response) throws Exception {
			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(body(response).get("code").asString()).isEqualTo("INVALID_REQUEST_BODY");
			verifyNoInteractions(service);
		}
	}

	@Nested
	@DisplayName("누락·null·원본 범위 위반 → 400 INVALID_JOB_POSTING + field, 서비스 호출 없음")
	class Invalid {
		@ParameterizedTest(name = "latitude={0}")
		@ValueSource(strings = {"null", "90.0000004", "-90.0000004", "90.0000000000000001", "91", "9.00000004E1", "1e1000"})
		@DisplayName("위도만 잘못되면 field=latitude (90.0000004는 반올림되어 통과하지 않음)")
		void latitude(String raw) throws Exception {
			assertInvalid(send(raw, "127.1"), "latitude");
		}

		@ParameterizedTest(name = "longitude={0}")
		@ValueSource(strings = {"null", "180.0000004", "-180.0000004", "180.0000000000000001", "181"})
		@DisplayName("경도만 잘못되면 field=longitude")
		void longitude(String raw) throws Exception {
			assertInvalid(send("37.1", raw), "longitude");
		}

		@Test
		@DisplayName("한쪽 필드 자체가 없으면 그 필드로 거절")
		void missingField() throws Exception {
			assertInvalid(sendRaw(TEMPLATE.formatted("\"latitude\":37.1,")), "longitude");
			assertInvalid(sendRaw(TEMPLATE.formatted("\"longitude\":127.1,")), "latitude");
		}

		@Test
		@DisplayName("둘 다 없으면 latitude 또는 longitude 중 하나로 400 (어느 쪽이 먼저인지는 보장되지 않음)")
		void bothMissing() throws Exception {
			var response = sendRaw(TEMPLATE.formatted(""));

			assertThat(response.getStatus()).isEqualTo(400);
			JsonNode body = body(response);
			assertThat(body.get("code").asString()).isEqualTo("INVALID_JOB_POSTING");
			assertThat(body.get("field").asString()).isIn("latitude", "longitude");
			verifyNoInteractions(service);
		}

		private void assertInvalid(MockHttpServletResponse response, String field) throws Exception {
			assertThat(response.getStatus()).isEqualTo(400);
			JsonNode body = body(response);
			assertThat(body.get("code").asString()).isEqualTo("INVALID_JOB_POSTING");
			assertThat(body.get("field").asString()).isEqualTo(field);
			verifyNoInteractions(service);
		}
	}

	private MockHttpServletResponse send(String latitude, String longitude) throws Exception {
		return sendRaw(TEMPLATE.formatted("\"latitude\":" + latitude + ",\"longitude\":" + longitude + ","));
	}

	private MockHttpServletResponse sendRaw(String json) throws Exception {
		return mvc.perform(post("/api/job-postings").contentType(MediaType.APPLICATION_JSON).content(json))
			.andReturn().getResponse();
	}

	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}
}

package com.wonday.farm.insurance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.insurance.dto.VerifiedWorkerResponse;
import com.wonday.farm.insurance.dto.WorkAssignmentResponse;
import com.wonday.farm.insurance.dto.WorkChangesResponse;
import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.exception.InsuranceErrorHandler;
import com.wonday.farm.insurance.service.WorkAssignmentQueryService;
import com.wonday.farm.insurance.service.WorkChangeQueryService;
import com.wonday.farm.insurance.service.WorkerVerificationService;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc(컨트롤러 + InsuranceErrorHandler + 기본 메시지 변환기)로 경로·파라미터 전달, 오류 응답, JSON 표현을 본다.
// Spring 컨텍스트·Security 필터 체인은 없다. 서버 토큰 인증, 체인 분리, Spring Boot가 구성한 JsonMapper 설정,
// /error 디스패치는 여기서 검증하지 않는다 (통합 검증 대상).
class InsuranceIntegrationApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String BASE = "/api/internal/insurance";

	private final WorkerVerificationService verification = mock(WorkerVerificationService.class);
	private final WorkAssignmentQueryService assignments = mock(WorkAssignmentQueryService.class);
	private final WorkChangeQueryService changes = mock(WorkChangeQueryService.class);
	private final MockMvc mvc = MockMvcBuilders
		.standaloneSetup(new InsuranceIntegrationApi(verification, assignments, changes))
		.setControllerAdvice(new InsuranceErrorHandler())
		.build();

	@Nested
	class Verification {
		@Test
		void passesNormalizedBodyToServiceAndReturnsOnlyIdNameAndPhone() throws Exception {
			given(verification.verify(any())).willReturn(new VerifiedWorkerResponse(7L, "데모 근로자", "01000000000"));

			var response = perform(post(BASE + "/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\" 데모 근로자 \",\"phone\":\"010-0000-0000\",\"residentRegistrationNumber\":\"000000-0000000\"}"));

			var captor = ArgumentCaptor.forClass(WorkerVerificationRequest.class);
			verify(verification).verify(captor.capture());
			assertThat(captor.getValue()).isEqualTo(new WorkerVerificationRequest("데모 근로자", "01000000000", "0000000000000"));
			assertThat(response.getStatus()).isEqualTo(200);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactly("workerId", "name", "phone");
			assertThat(body.get("workerId").isIntegralNumber()).isTrue();
			assertThat(body.get("workerId").asLong()).isEqualTo(7L);
			assertThat(response.getContentAsString()).doesNotContain("0000000000000");
		}

		@Test
		void numericPhoneIsInvalidRequestAndNotEchoed() throws Exception {
			var response = perform(post(BASE + "/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"데모\",\"phone\":1012345678,\"residentRegistrationNumber\":\"900101-1234567\"}"));

			assertInvalidRequest(response);
			assertThat(response.getContentAsString()).doesNotContain("1012345678").doesNotContain("900101");
			verifyNoInteractions(verification);
		}

		@Test
		void blankFieldIsInvalidRequestWithoutFieldDetails() throws Exception {
			var response = perform(post(BASE + "/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"홍길동\",\"phone\":\" \",\"residentRegistrationNumber\":\"9001011234567\"}"));

			assertInvalidRequest(response);
			assertThat(response.getContentAsString()).doesNotContain("홍길동").doesNotContain("9001011234567")
				.doesNotContain("phone");
			verifyNoInteractions(verification);
		}

		@ParameterizedTest(name = "body={0}")
		@ValueSource(strings = {"", "{", "[]", "null", "{\"name\":\"데모\",\"phone\":\"0100000000\",}"})
		void unreadableBodyIsInvalidRequest(String body) throws Exception {
			assertInvalidRequest(perform(post(BASE + "/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content(body)));
			verifyNoInteractions(verification);
		}

		@Test
		void serviceErrorsKeepTheirCodeAndStatus() throws Exception {
			given(verification.verify(any())).willThrow(new InsuranceApiException(HttpStatus.CONFLICT,
				"WORKER_IDENTITY_AMBIGUOUS", "입력한 정보로 근로자를 한 명으로 확인할 수 없습니다."));

			var response = perform(post(BASE + "/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"데모\",\"phone\":\"01000000000\",\"residentRegistrationNumber\":\"0000000000000\"}"));

			assertThat(response.getStatus()).isEqualTo(409);
			assertThat(json(response).get("code").asString()).isEqualTo("WORKER_IDENTITY_AMBIGUOUS");
		}
	}

	@Nested
	class Assignment {
		@Test
		void passesBothPathIdsToService() throws Exception {
			given(assignments.find(5L, 31L)).willReturn(assignment(null, null));

			assertThat(perform(get(BASE + "/workers/5/work-assignments/31")).getStatus()).isEqualTo(200);
			verify(assignments).find(5L, 31L);
		}

		@ParameterizedTest(name = "path={0}")
		@ValueSource(strings = {"/workers/abc/work-assignments/31", "/workers/5/work-assignments/1.5",
			"/workers/99999999999999999999/work-assignments/31"})
		void nonNumericPathIdIsInvalidRequest(String path) throws Exception {
			assertInvalidRequest(perform(get(BASE + path)));
			verifyNoInteractions(assignments);
		}

		@Test
		void serializesDatesOffsetsAndNullBreaks() throws Exception {
			given(assignments.find(5L, 31L)).willReturn(assignment(null, null));

			JsonNode body = json(perform(get(BASE + "/workers/5/work-assignments/31")));

			assertThat(body.get("agreedWorkDate").asString()).isEqualTo("2026-11-30");
			assertThat(body.get("agreedTimeZone").asString()).isEqualTo("GMT+9");
			assertThat(body.get("confirmedAt").asString()).isEqualTo("2026-11-20T10:02:03.456789+09:00");
			assertThat(OffsetDateTime.parse(body.get("agreedStartAt").asString()))
				.isEqualTo(OffsetDateTime.parse("2026-11-30T08:00+09:00"));
			assertThat(body.get("agreedStartAt").asString()).endsWith("+09:00");
			assertThat(body.has("agreedBreakStartAt")).isTrue();
			assertThat(body.get("agreedBreakStartAt").isNull()).isTrue();
			assertThat(body.get("agreedBreakEndAt").isNull()).isTrue();
			assertThat(body.get("agreedDailyWage").isIntegralNumber()).isTrue();
		}

		@Test
		void breakTimesAreSerializedWhenPresent() throws Exception {
			given(assignments.find(5L, 31L)).willReturn(assignment(OffsetDateTime.parse("2026-11-30T12:00+09:00"),
				OffsetDateTime.parse("2026-11-30T13:00+09:00")));

			JsonNode body = json(perform(get(BASE + "/workers/5/work-assignments/31")));

			assertThat(OffsetDateTime.parse(body.get("agreedBreakStartAt").asString()))
				.isEqualTo(OffsetDateTime.parse("2026-11-30T12:00+09:00"));
			assertThat(body.get("agreedBreakEndAt").asString()).endsWith("+09:00");
		}
	}

	@Nested
	class Changes {
		@Test
		void passesCursorAndLimitToService() throws Exception {
			given(changes.poll(5L, "c-1", 20)).willReturn(new WorkChangesResponse(5L, List.of(), "c-2", false));

			perform(get(BASE + "/workers/5/work-changes").param("cursor", "c-1").param("limit", "20"));

			verify(changes).poll(5L, "c-1", 20);
		}

		@Test
		void missingCursorIsNullAndMissingLimitIs100() throws Exception {
			given(changes.poll(anyLong(), isNull(), anyInt())).willReturn(new WorkChangesResponse(5L, List.of(), "c", false));

			perform(get(BASE + "/workers/5/work-changes"));

			verify(changes).poll(5L, null, 100);
		}

		@ParameterizedTest(name = "limit={0}")
		@ValueSource(strings = {"abc", "1.5", "99999999999"})
		void nonIntegerLimitIsInvalidRequest(String limit) throws Exception {
			assertInvalidRequest(perform(get(BASE + "/workers/5/work-changes").param("limit", limit)));
			verifyNoInteractions(changes);
		}

		@Test
		void invalidCursorErrorKeepsItsCode() throws Exception {
			given(changes.poll(5L, "bad", 100)).willThrow(new InsuranceApiException(HttpStatus.BAD_REQUEST,
				"INVALID_CURSOR", "유효한 해당 근로자의 커서를 사용해 주세요."));

			var response = perform(get(BASE + "/workers/5/work-changes").param("cursor", "bad"));

			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(json(response).get("code").asString()).isEqualTo("INVALID_CURSOR");
		}

		@Test
		void emptyListIsAnEmptyArrayWithCursor() throws Exception {
			given(changes.poll(5L, null, 100)).willReturn(new WorkChangesResponse(5L, List.of(), "cursor-0", false));

			JsonNode body = json(perform(get(BASE + "/workers/5/work-changes")));

			assertThat(body.get("items").isArray()).isTrue();
			assertThat(body.get("items").size()).isZero();
			assertThat(body.get("nextCursor").asString()).isEqualTo("cursor-0");
			assertThat(body.get("hasMore").isBoolean()).isTrue();
		}

		@Test
		void itemSerializesSourceVersionAsStringEnumsAsNamesAndOffsets() throws Exception {
			var item = new WorkChangesResponse.Item(201L, "2", 31L, WorkChange.Type.EARLY_ENDED,
				WorkChange.Reason.FARM_CIRCUMSTANCES, null, null,
				OffsetDateTime.parse("2026-11-30T15:00+09:00"), OffsetDateTime.parse("2026-11-30T15:00:00.000001+09:00"),
				OffsetDateTime.parse("2026-11-30T16:30+09:00"));
			given(changes.poll(5L, null, 100)).willReturn(new WorkChangesResponse(5L, List.of(item), "c", true));

			JsonNode body = json(perform(get(BASE + "/workers/5/work-changes"))).get("items").get(0);

			assertThat(body.get("sourceVersion").isString()).isTrue();
			assertThat(body.get("sourceVersion").asString()).isEqualTo("2");
			assertThat(body.get("changeType").asString()).isEqualTo("EARLY_ENDED");
			assertThat(body.get("reasonCode").asString()).isEqualTo("FARM_CIRCUMSTANCES");
			assertThat(body.get("weatherReasonCode").isNull()).isTrue();
			assertThat(body.get("reasonDetail").isNull()).isTrue();
			assertThat(body.get("recordedAt").asString()).isEqualTo("2026-11-30T15:00:00.000001+09:00");
			assertThat(OffsetDateTime.parse(body.get("effectiveAt").asString()))
				.isEqualTo(OffsetDateTime.parse("2026-11-30T15:00+09:00"));
			assertThat(body.get("updatedAt").asString()).endsWith("+09:00");
		}
	}

	@Nested
	class ErrorResponses {
		static Stream<Arguments> transientFailures() {
			return Stream.of(
				Arguments.of(new DataAccessResourceFailureException("db down: jdbc:mysql://10.0.0.1/farm_db")),
				Arguments.of(new QueryTimeoutException("timeout: select * from member")),
				Arguments.of(new CannotAcquireLockException("lock wait timeout")),
				Arguments.of(new PessimisticLockingFailureException("deadlock")),
				Arguments.of(new CannotCreateTransactionException("no connection")),
				Arguments.of(new TransactionTimedOutException("timed out")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("transientFailures")
		void transientDatabaseOrTransactionFailureIs503WithoutInternalDetails(RuntimeException failure) throws Exception {
			given(changes.poll(5L, null, 100)).willThrow(failure);

			var response = perform(get(BASE + "/workers/5/work-changes"));

			assertThat(response.getStatus()).isEqualTo(503);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
			assertThat(response.getContentAsString()).doesNotContain(failure.getMessage());
		}

		@Test
		void unexpectedErrorIs500WithoutInternalDetails() throws Exception {
			given(assignments.find(5L, 31L)).willThrow(new IllegalStateException("resident 9001011234567 at Foo.java:12"));

			var response = perform(get(BASE + "/workers/5/work-assignments/31"));

			assertThat(response.getStatus()).isEqualTo(500);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
			assertThat(response.getContentAsString()).doesNotContain("9001011234567").doesNotContain("Foo.java");
		}

		@Test
		void nonTransientDataErrorIs500() throws Exception {
			given(assignments.find(5L, 31L)).willThrow(new DataIntegrityViolationException("Duplicate entry"));

			var response = perform(get(BASE + "/workers/5/work-assignments/31"));

			assertThat(response.getStatus()).isEqualTo(500);
			assertThat(response.getContentAsString()).doesNotContain("Duplicate entry");
		}

		@Test
		void expectedApiErrorUsesCodeAndMessageOnly() throws Exception {
			given(assignments.find(5L, 31L)).willThrow(new InsuranceApiException(HttpStatus.NOT_FOUND,
				"WORK_ASSIGNMENT_NOT_FOUND", "해당 근로자의 확정 근무를 찾을 수 없습니다."));

			var response = perform(get(BASE + "/workers/5/work-assignments/31"));

			assertThat(response.getStatus()).isEqualTo(404);
			JsonNode body = json(response);
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("WORK_ASSIGNMENT_NOT_FOUND");
			assertThat(body.get("message").asString()).isEqualTo("해당 근로자의 확정 근무를 찾을 수 없습니다.");
		}

		@Test
		void unsupportedContentTypeIsInvalidRequestInCodeMessageFormat() throws Exception {
			// InsuranceErrorHandler는 HttpMediaTypeNotSupportedException을 400 INVALID_REQUEST로 처리하도록 선언했다.
			var response = perform(post(BASE + "/worker-verifications").contentType(MediaType.TEXT_PLAIN)
				.content("{\"name\":\"데모\",\"phone\":\"01000000000\",\"residentRegistrationNumber\":\"0000000000000\"}"));

			assertInvalidRequest(response);
			verifyNoInteractions(verification);
		}
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static JsonNode json(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static void assertInvalidRequest(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).isEqualTo(400);
		JsonNode body = json(response);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(body.get("code").asString()).isEqualTo("INVALID_REQUEST");
	}

	private static WorkAssignmentResponse assignment(OffsetDateTime breakStart, OffsetDateTime breakEnd) {
		return new WorkAssignmentResponse(31L, 5L, OffsetDateTime.parse("2026-11-20T10:02:03.456789+09:00"),
			LocalDate.parse("2026-11-30"), "GMT+9", "약정 작업지", "AGREED-CROP", "AGREED-WORK",
			OffsetDateTime.parse("2026-11-30T08:00+09:00"), OffsetDateTime.parse("2026-11-30T17:00+09:00"),
			breakStart, breakEnd, 120000L);
	}
}

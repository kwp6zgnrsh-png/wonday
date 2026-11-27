package com.wonday.farm.work.api;

import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.assignment;
import static com.wonday.farm.insurance.testsupport.InsuranceFixtures.member;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.WorkCompletionResponse;
import com.wonday.farm.work.exception.WorkCompletionErrorHandler;
import com.wonday.farm.work.exception.WorkCompletionException;
import com.wonday.farm.work.repository.WorkAssignmentRepository;
import com.wonday.farm.work.service.WorkCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 WorkCompletionApi + 실제 WorkCompletionErrorHandler + 기본 메시지 변환기.
// principal은 SecurityContext에 직접 넣는다. 실제 Security 체인(익명 401, hasRole("EMPLOYER") 403 핸들러, CSRF 차단,
// 세션 격리)과 Spring Boot가 구성한 JsonMapper 설정은 이 테스트에 없다 (통합 검증 대상).
@DisplayName("근무 완료 API (standalone MockMvc, principal 직접 주입)")
class WorkCompletionApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long EMPLOYER_ID = 10L;
	private static final String URL = "/api/work-assignments/31/completion";
	private static final String SECRET = "jdbc:mysql://10.0.0.5/farm_db?password=s3cr3t worker@example.com 010-1234-5678";

	private final WorkCompletionService service = mock(WorkCompletionService.class);
	private MockMvc mvc = build(service);

	private static MockMvc build(WorkCompletionService target) {
		return MockMvcBuilders.standaloneSetup(new WorkCompletionApi(target)).setControllerAdvice(new WorkCompletionErrorHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
	}

	@BeforeEach
	void signedInEmployer() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(EMPLOYER_ID);
		given(principal.role()).willReturn(MemberRole.EMPLOYER);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(service.complete(anyLong(), anyLong())).willReturn(
			new WorkCompletionResponse(31L, OffsetDateTime.parse("2026-11-30T17:00:00.123456+09:00")));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("성공")
	class Success {
		@Test
		@DisplayName("본문 없는 POST → 200, principal의 고용주 ID와 경로 ID로 서비스 호출, JSON은 assignmentId·completedAt 두 필드")
		void completes() throws Exception {
			var response = perform(post(URL));

			assertThat(response.getStatus()).isEqualTo(200);
			verify(service).complete(EMPLOYER_ID, 31L);
			JsonNode body = parse(response);
			assertThat(body.propertyNames()).containsExactly("assignmentId", "completedAt");
			assertThat(body.get("assignmentId").asLong()).isEqualTo(31L);
			assertThat(body.get("completedAt").asString()).isEqualTo("2026-11-30T17:00:00.123456+09:00");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("completedBy", "10");
			assertThat(response.getContentType()).startsWith("application/json");
			assertNoStore(response);
		}

		@Test
		@DisplayName("본문·쿼리로 다른 처리자·시각을 보내도 무시하고 세션 고용주 ID만 사용한다")
		void ignoresClientSuppliedValues() throws Exception {
			var response = perform(post(URL).param("employerId", "99").param("completedBy", "99")
				.contentType(MediaType.APPLICATION_JSON).content("{\"completedBy\":99,\"completedAt\":\"2020-01-01T00:00Z\",\"employerId\":99}"));

			assertThat(response.getStatus()).isEqualTo(200);
			verify(service).complete(EMPLOYER_ID, 31L);
		}

		@Test
		@DisplayName("마이크로초 0인 시각과 다른 offset도 서비스가 준 값 그대로 직렬화")
		void serializesOffsetAsGiven() throws Exception {
			given(service.complete(anyLong(), anyLong())).willReturn(new WorkCompletionResponse(31L, OffsetDateTime.parse("2026-11-30T17:00-05:00")));

			var body = parse(perform(post(URL)));

			assertThat(body.get("completedAt").asString()).isEqualTo("2026-11-30T17:00:00-05:00");
		}

		@Test
		@DisplayName("실제 서비스(Repository mock·고정 Clock)와 연결: 저장 UTC 09:00:00.5 → 응답 18:00:00.5+09:00")
		void withRealService() throws Exception {
			var members = mock(MemberRepository.class);
			var assignments = mock(WorkAssignmentRepository.class);
			var changes = mock(WorkChangeRepository.class);
			given(members.findById(EMPLOYER_ID)).willReturn(Optional.of(member(EMPLOYER_ID, MemberRole.EMPLOYER)));
			given(assignments.findOwnership(31L)).willReturn(Optional.of(new WorkAssignmentRepository.Ownership() {
				public long getWorkerId() { return 5L; }
				public long getEmployerId() { return EMPLOYER_ID; }
			}));
			var stored = assignment(31L);
			given(assignments.findByIdForUpdate(31L)).willReturn(Optional.of(stored));
			given(changes.findForUpdate(31L)).willReturn(Optional.empty());
			mvc = build(new WorkCompletionService(members, assignments, changes,
				Clock.fixed(Instant.parse("2026-11-30T09:00:00.5Z"), ZoneOffset.UTC)));

			var response = perform(post(URL));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(parse(response).get("completedAt").asString()).isEqualTo("2026-11-30T18:00:00.5+09:00");
			assertThat(stored.getCompletedBy()).isEqualTo(EMPLOYER_ID);

			var invalid = perform(post("/api/work-assignments/0/completion"));
			assertError(invalid, 400, "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("잘못된 경로 ID")
	class InvalidId {
		@ParameterizedTest(name = "id={0}")
		@ValueSource(strings = {"abc", "1.5", "9223372036854775808", "-9223372036854775809", "1e3"})
		@DisplayName("숫자가 아니거나 long 범위를 넘는 ID는 400 INVALID_REQUEST이고 서비스 호출 없음")
		void typeMismatch(String id) throws Exception {
			var response = perform(post("/api/work-assignments/" + id + "/completion"));

			assertError(response, 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("[특성화] Spring의 long 변환은 16진수(0x1F)를 31로 받아 서비스에 넘긴다 (기존 다른 API와 같은 변환 규칙)")
		void lenientConversion() throws Exception {
			var response = perform(post("/api/work-assignments/0x1F/completion"));

			assertThat(response.getStatus()).isEqualTo(200);
			verify(service).complete(EMPLOYER_ID, 31L);
		}
	}

	@Nested
	@DisplayName("서비스 예외 → 오류 응답")
	class Errors {
		static Stream<Arguments> expected() {
			return Stream.of(
				Arguments.of(WorkCompletionException.invalidRequest(), 400, "INVALID_REQUEST"),
				Arguments.of(WorkCompletionException.notFound(), 404, "WORK_ASSIGNMENT_NOT_FOUND"),
				Arguments.of(new WorkCompletionException(HttpStatus.CONFLICT, "WORK_ALREADY_CANCELLED", "취소"), 409, "WORK_ALREADY_CANCELLED"),
				Arguments.of(new WorkCompletionException(HttpStatus.CONFLICT, "WORK_ALREADY_EARLY_ENDED", "조기"), 409, "WORK_ALREADY_EARLY_ENDED"),
				Arguments.of(new WorkCompletionException(HttpStatus.CONFLICT, "WORK_NOT_FINISHED", "종료 전"), 409, "WORK_NOT_FINISHED"),
				Arguments.of(WorkCompletionException.unavailable(), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new AccessDeniedException(SECRET), 403, "EMPLOYER_ROLE_REQUIRED"),
				Arguments.of(new DataAccessResourceFailureException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotAcquireLockException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new QueryTimeoutException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotCreateTransactionException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new TransactionTimedOutException(SECRET), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new DataIntegrityViolationException(SECRET), 500, "INTERNAL_SERVER_ERROR"),
				Arguments.of(new IllegalStateException(SECRET), 500, "INTERNAL_SERVER_ERROR"),
				Arguments.of(new RuntimeException(SECRET, new IllegalArgumentException(SECRET)), 500, "INTERNAL_SERVER_ERROR"));
		}

		@ParameterizedTest(name = "{0} → {1} {2}")
		@MethodSource("expected")
		@DisplayName("상태코드·오류 코드 매핑, 본문은 code·message뿐, no-store, 예외 상세·개인정보 미노출")
		void mapsErrors(RuntimeException exception, int status, String code) throws Exception {
			given(service.complete(anyLong(), anyLong())).willThrow(exception);

			var response = perform(post(URL));

			assertError(response, status, code);
			String text = response.getContentAsString(StandardCharsets.UTF_8);
			assertThat(text).doesNotContain("jdbc", "s3cr3t", "example.com", "010-1234", "Exception", "completedBy", "trace");
		}

		@Test
		@DisplayName("principal이 없으면(standalone에서 인증 생략) 403 EMPLOYER_ROLE_REQUIRED이고 서비스 호출 없음 — 실제 체인의 익명 401과는 별개")
		void missingPrincipal() throws Exception {
			SecurityContextHolder.clearContext();

			assertError(perform(post(URL)), 403, "EMPLOYER_ROLE_REQUIRED");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("GET은 매핑되지 않아 405이고 서비스 호출 없음 (핸들러 선택 전 예외라 이 advice 밖의 기본 응답)")
		void getIsNotMapped() throws Exception {
			assertThat(perform(get(URL)).getStatus()).isEqualTo(405);
			verifyNoInteractions(service);
		}
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
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
		assertThat(body.get("message").asString()).isNotBlank();
	}
}

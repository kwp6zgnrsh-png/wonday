package com.wonday.farm.salary.api;

import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.JSON;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.SECRETS;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.body;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import com.wonday.farm.salary.dto.SalaryAccountRequest;
import com.wonday.farm.salary.dto.SalaryAccountResponse;
import com.wonday.farm.salary.exception.SalaryAccountErrorHandler;
import com.wonday.farm.salary.exception.SalaryAccountException;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import com.wonday.farm.salary.service.SalaryAccountService;
import com.wonday.farm.salary.testsupport.SalaryAccountTestData;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;

// standalone MockMvc: 실제 SalaryAccountApi + 실제 SalaryAccountErrorHandler + 기본 메시지 변환기·Bean Validation.
// principal은 SecurityContext에 직접 넣는다. 실제 로그인·세션·CSRF 차단·SecurityConfig의 hasRole("WORKER")·
// 필터의 403 응답은 이 테스트에 없다 (통합 검증 대상).
@DisplayName("급여 계좌 API 컨트롤러 (standalone MockMvc, principal 직접 주입)")
class SalaryAccountApiTest {
	private static final String URL = "/api/me/salary-account";
	private static final long ID = 101L;

	private final SalaryAccountService service = mock(SalaryAccountService.class);
	private LocalValidatorFactoryBean validator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = build(service);
		signIn(ID, MemberRole.WORKER);
		given(service.findMine(anyLong())).willReturn(new SalaryAccountResponse(true, "020", "********0400", "TEST WORKER", 1));
		given(service.replace(anyLong(), any())).willReturn(new SalaryAccountResponse(true, "020", "********0400", "TEST WORKER", 2));
	}

	@AfterEach
	void tearDown() {
		validator.close();
		SecurityContextHolder.clearContext();
	}

	private MockMvc build(SalaryAccountService target) {
		return MockMvcBuilders.standaloneSetup(new SalaryAccountApi(target)).setControllerAdvice(new SalaryAccountErrorHandler())
			.setValidator(validator).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
	}

	private static void signIn(long id, MemberRole role) {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(id);
		given(principal.role()).willReturn(role);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private MockHttpServletResponse putJson(String content) throws Exception {
		return perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(content));
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoSecrets(MockHttpServletResponse response) throws Exception {
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRETS);
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode node = parse(response);
		assertThat(node.propertyNames()).containsExactly("code", "message");
		assertThat(node.get("code").asString()).isEqualTo(code);
		assertNoSecrets(response);
	}

	@Nested
	@DisplayName("정상 응답")
	class Ok {
		@Test
		@DisplayName("GET 등록: 200·no-store·다섯 필드·마스킹 번호, principal ID로 조회 (쿼리의 memberId 무시)")
		void getRegistered() throws Exception {
			MockHttpServletResponse response = perform(get(URL).param("memberId", "999"));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("registered", "bankCode", "maskedAccountNumber", "accountHolderName", "version");
			assertThat(node.get("registered").asBoolean()).isTrue();
			assertThat(node.get("bankCode").asString()).isEqualTo("020");
			assertThat(node.get("maskedAccountNumber").asString()).isEqualTo("********0400");
			assertThat(node.get("version").asLong()).isEqualTo(1);
			verify(service).findMine(ID);
		}

		@Test
		@DisplayName("GET 미등록: 200, 세 필드 JSON null, version은 0이 아닐 수 있다 (4)")
		void getUnregistered() throws Exception {
			given(service.findMine(anyLong())).willReturn(new SalaryAccountResponse(false, null, null, null, 4));
			JsonNode node = parse(perform(get(URL)));
			assertThat(node.get("registered").asBoolean()).isFalse();
			assertThat(node.get("bankCode").isNull()).isTrue();
			assertThat(node.get("maskedAccountNumber").isNull()).isTrue();
			assertThat(node.get("accountHolderName").isNull()).isTrue();
			assertThat(node.get("version").asLong()).isEqualTo(4);
		}

		@Test
		@DisplayName("PUT 200·no-store: 요청 원문 DTO와 principal ID를 서비스에 전달, 새 version 응답")
		void put200() throws Exception {
			MockHttpServletResponse response = putJson(json(body(1)));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(parse(response).get("version").asLong()).isEqualTo(2);
			assertNoSecrets(response);
			verify(service).replace(ID, new SalaryAccountRequest("020", "1002-0030-0400", "TEST WORKER", 1L));
		}

		@Test
		@DisplayName("DELETE 204·빈 본문·no-store, 쿼리 version과 principal ID 전달 (memberId 쿼리 무시)")
		void delete204() throws Exception {
			MockHttpServletResponse response = perform(delete(URL).param("version", "3").param("memberId", "999"));

			assertThat(response.getStatus()).isEqualTo(204);
			assertThat(response.getContentAsByteArray()).isEmpty();
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			verify(service).remove(ID, 3);
		}

		@Test
		@DisplayName("DELETE version 경계: 0과 Long 최대값(19자리) 허용, 앞자리 0은 숫자로 해석")
		void deleteBoundaries() throws Exception {
			assertThat(perform(delete(URL).param("version", "0")).getStatus()).isEqualTo(204);
			assertThat(perform(delete(URL).param("version", "9223372036854775807")).getStatus()).isEqualTo(204);
			assertThat(perform(delete(URL).param("version", "007")).getStatus()).isEqualTo(204);
			verify(service).remove(ID, 0);
			verify(service).remove(ID, Long.MAX_VALUE);
			verify(service).remove(ID, 7);
		}

		@Test
		@DisplayName("실제 서비스 연결(Repository mock + 테스트 키): 미등록 GET → PUT 등록 → GET 마스킹 → DELETE 204")
		void realServiceFlow() throws Exception {
			MemberRepository members = mock(MemberRepository.class);
			MemberSalaryAccountRepository accounts = mock(MemberSalaryAccountRepository.class);
			var member = SalaryAccountTestData.member(ID, "WORKER", 0);
			var stored = new java.util.concurrent.atomic.AtomicReference<com.wonday.farm.salary.entity.MemberSalaryAccount>();
			given(members.findById(ID)).willReturn(Optional.of(member));
			given(members.findByIdForSalaryAccountUpdate(ID)).willReturn(Optional.of(member));
			given(accounts.findById(ID)).willAnswer(invocation -> Optional.ofNullable(stored.get()));
			given(accounts.saveAndFlush(any())).willAnswer(invocation -> { stored.set(invocation.getArgument(0)); return invocation.getArgument(0); });
			org.mockito.BDDMockito.willAnswer(invocation -> { stored.set(null); return null; }).given(accounts).delete(any());
			mvc = build(new SalaryAccountService(members, accounts, SalaryAccountTestData.crypto(), Clock.systemUTC()));

			assertThat(parse(perform(get(URL))).get("registered").asBoolean()).isFalse();
			MockHttpServletResponse saved = putJson(json(body(0)));
			assertThat(saved.getStatus()).isEqualTo(200);
			assertThat(parse(saved).get("maskedAccountNumber").asString()).isEqualTo("********0400");
			assertNoSecrets(saved);
			assertThat(parse(perform(get(URL))).get("version").asLong()).isEqualTo(1);
			assertError(putJson(json(body(0))), 409, "SALARY_ACCOUNT_VERSION_CONFLICT");
			Map<String, Object> masked = body(1);
			masked.put("accountNumber", "********0400");
			assertError(putJson(json(masked)), 400, "INVALID_REQUEST");
			assertThat(perform(delete(URL).param("version", "1")).getStatus()).isEqualTo(204);
			assertThat(perform(delete(URL).param("version", "0")).getStatus()).isEqualTo(204);
			assertThat(parse(perform(get(URL))).get("version").asLong()).isEqualTo(2);
		}
	}

	@Nested
	@DisplayName("400 INVALID_REQUEST — 서비스 호출 없음")
	class BadRequest {
		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"{", "", "[]", "null", "\"text\"", "1"})
		@DisplayName("PUT 깨진 JSON·빈 본문·배열·null·문자열·숫자 본문")
		void malformed(String raw) throws Exception {
			assertError(putJson(raw), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		static Stream<Arguments> invalidBodies() {
			return Stream.of(
				Arguments.of("은행 숫자", "bankCode", 20),
				Arguments.of("번호 숫자", "accountNumber", 100200300400L),
				Arguments.of("예금주 boolean", "accountHolderName", true),
				Arguments.of("번호 배열", "accountNumber", List.of("1002")),
				Arguments.of("은행 객체", "bankCode", Map.of("v", "020")),
				Arguments.of("은행 null", "bankCode", null),
				Arguments.of("번호 빈 값", "accountNumber", ""),
				Arguments.of("예금주 공백", "accountHolderName", "   "),
				Arguments.of("은행 4자", "bankCode", "0200"),
				Arguments.of("번호 101자", "accountNumber", "1".repeat(101)),
				Arguments.of("예금주 101자", "accountHolderName", "가".repeat(101)),
				Arguments.of("version 음수", "version", -1),
				Arguments.of("version 소수", "version", 1.5),
				Arguments.of("version 숫자 문자열", "version", "1"),
				Arguments.of("version boolean", "version", true),
				Arguments.of("version null", "version", null),
				Arguments.of("version 범위 초과", "version", new java.math.BigInteger("9223372036854775808")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidBodies")
		@DisplayName("PUT 필드 형식·필수·길이·version 오류")
		void invalidField(String label, String field, Object value) throws Exception {
			Map<String, Object> request = body(0);
			request.put(field, value);
			assertError(putJson(json(request)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("PUT 네 필드 각각 누락은 400")
		void missing() throws Exception {
			for (String field : new String[] {"bankCode", "accountNumber", "accountHolderName", "version"}) {
				Map<String, Object> request = body(0);
				request.remove(field);
				assertError(putJson(json(request)), 400, "INVALID_REQUEST");
			}
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("PUT version 1.0·지수 표기는 정수 JSON이 아니므로 400")
		void versionNotInteger() throws Exception {
			assertError(putJson("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":1.0}"), 400, "INVALID_REQUEST");
			assertError(putJson("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":1e0}"), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "version=[{0}]")
		@ValueSource(strings = {"", "-1", "1.0", "1e2", "abc", "+1", " 1", "1 ", "0x1", "１", "99999999999999999999", "9223372036854775808"})
		@DisplayName("DELETE version 형식 오류·범위 초과는 400")
		void deleteInvalid(String version) throws Exception {
			assertError(perform(delete(URL).param("version", version)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("DELETE version 누락·중복 파라미터(같은 값 포함)는 400")
		void deleteMissingOrDuplicate() throws Exception {
			assertError(perform(delete(URL)), 400, "INVALID_REQUEST");
			assertError(perform(delete(URL).param("version", "1", "2")), 400, "INVALID_REQUEST");
			assertError(perform(delete(URL).param("version", "1", "1")), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}
	}

	@Nested
	@DisplayName("403·409·503·500·415")
	class Errors {
		@Test
		@DisplayName("principal 없음·고용주·회원 ID 0: 세 메서드 모두 403 WORKER_ROLE_REQUIRED, 서비스 미호출")
		void forbiddenPrincipal() throws Exception {
			for (Runnable login : new Runnable[] {SecurityContextHolder::clearContext, () -> signIn(ID, MemberRole.EMPLOYER), () -> signIn(0, MemberRole.WORKER)}) {
				login.run();
				assertError(perform(get(URL)), 403, "WORKER_ROLE_REQUIRED");
				assertError(putJson(json(body(0))), 403, "WORKER_ROLE_REQUIRED");
				assertError(perform(delete(URL).param("version", "0")), 403, "WORKER_ROLE_REQUIRED");
			}
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("DELETE는 권한을 먼저 본다: 고용주의 잘못된 version도 403")
		void deleteForbiddenBeforeVersion() throws Exception {
			signIn(ID, MemberRole.EMPLOYER);
			assertError(perform(delete(URL).param("version", "abc")), 403, "WORKER_ROLE_REQUIRED");
		}

		@Test
		@DisplayName("서비스의 403(DB 역할 변경)·409(PUT·DELETE)를 그대로 변환")
		void serviceErrors() throws Exception {
			willThrow(SalaryAccountException.forbidden()).given(service).findMine(anyLong());
			assertError(perform(get(URL)), 403, "WORKER_ROLE_REQUIRED");
			willThrow(SalaryAccountException.conflict()).given(service).replace(anyLong(), any());
			assertError(putJson(json(body(0))), 409, "SALARY_ACCOUNT_VERSION_CONFLICT");
			willThrow(SalaryAccountException.conflict()).given(service).remove(anyLong(), eq(1L));
			assertError(perform(delete(URL).param("version", "1")), 409, "SALARY_ACCOUNT_VERSION_CONFLICT");
		}

		static Stream<Arguments> unavailable() {
			return Stream.of(
				Arguments.of(new SalaryAccountCryptoUnavailableException()),
				Arguments.of(SalaryAccountException.unavailable()),
				Arguments.of(new DataAccessResourceFailureException("Communications link failure jdbc 100200300400")),
				Arguments.of(new QueryTimeoutException("select account_number_ciphertext from member_salary_account")),
				Arguments.of(new CannotAcquireLockException("SQL lock wait 1002-0030-0400")),
				Arguments.of(new CannotCreateTransactionException("Exception jdbc")),
				Arguments.of(new TransactionTimedOutException("timeout v1:abc")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unavailable")
		@DisplayName("키·암호문 오류·일시적 DB·트랜잭션 장애는 503 SERVICE_UNAVAILABLE, 원문 비노출 (GET·PUT·DELETE)")
		void serviceUnavailable(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).findMine(anyLong());
			willThrow(exception).given(service).replace(anyLong(), any());
			willThrow(exception).given(service).remove(anyLong(), anyLong());
			assertError(perform(get(URL)), 503, "SERVICE_UNAVAILABLE");
			assertError(putJson(json(body(0))), 503, "SERVICE_UNAVAILABLE");
			assertError(perform(delete(URL).param("version", "0")), 503, "SERVICE_UNAVAILABLE");
		}

		static Stream<Arguments> unexpected() {
			return Stream.of(
				Arguments.of(new BadSqlGrammarException("select", "select * from member_salary_account",
					new java.sql.SQLException("SQL syntax 100200300400"))),
				Arguments.of(new IllegalStateException("v1:abc 100200300400")),
				Arguments.of(new ArithmeticException("long overflow")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unexpected")
		@DisplayName("기타 예외는 500 INTERNAL_SERVER_ERROR, 원문 비노출")
		void internal(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).replace(anyLong(), any());
			assertError(putJson(json(body(0))), 500, "INTERNAL_SERVER_ERROR");
		}

		@Test
		@DisplayName("PUT의 JSON이 아닌 Content-Type은 Spring 기본 415 (문서대로 code/message 형식 비보장), 서비스 미호출, 원문 비노출")
		void unsupportedMediaType() throws Exception {
			MockHttpServletResponse response = perform(put(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("bankCode=020&accountNumber=1002-0030-0400&accountHolderName=A&version=0"));
			assertThat(response.getStatus()).isEqualTo(415);
			assertNoSecrets(response);
			verifyNoInteractions(service);
		}
	}
}

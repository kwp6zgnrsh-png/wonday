package com.wonday.farm.member.signup.api;

import static com.wonday.farm.member.signup.testsupport.SignupTestData.account;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.body;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.signup.dto.SignupRequest;
import com.wonday.farm.member.signup.dto.SignupResponse;
import com.wonday.farm.member.signup.exception.SignupErrorHandler;
import com.wonday.farm.member.signup.exception.SignupException;
import com.wonday.farm.member.signup.service.SignupService;
import com.wonday.farm.salary.crypto.SalaryAccountCryptoUnavailableException;
import org.hibernate.exception.ConstraintViolationException;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 SignupApi + SignupErrorHandler + Bean Validator + 기본 메시지 변환기. SignupService는 mock.
// 실제 Security 필터·CSRF·세션 쿠키·동시 가입·DB 제약 발생은 검증하지 않는다. 제약 오류는 예외 객체로 모사한다.
@DisplayName("회원가입 API 응답·오류 변환 (standalone MockMvc)")
class SignupApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final List<String> SECRETS = List.of("Pass word!1", "111111-1111111", "1111111111111", "1002-0030-0400",
		"100200300400", "Duplicate entry", "insert into", "worker-01", "SQL", "Exception", "jdbc");

	private final SignupService service = mock(SignupService.class);
	private LocalValidatorFactoryBean validator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = MockMvcBuilders.standaloneSetup(new SignupApi(service))
			.setControllerAdvice(new SignupErrorHandler()).setValidator(validator).build();
		given(service.signup(any())).willReturn(new SignupResponse(101L, "worker-01", "TEST WORKER", MemberRole.WORKER, NationalityType.FOREIGN));
	}

	@AfterEach
	void tearDown() {
		validator.close();
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("정상")
	class Created {
		@Test
		@DisplayName("201과 id·loginId·name·role·nationalityType 5필드, 번호·계좌·비밀번호 비노출, 서비스에 원문 요청 전달")
		void created() throws Exception {
			MockHttpServletResponse response = send(json(body()));

			assertThat(response.getStatus()).isEqualTo(201);
			JsonNode node = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
			assertThat(node.propertyNames()).containsExactly("id", "loginId", "name", "role", "nationalityType");
			assertThat(node.get("id").asLong()).isEqualTo(101L);
			assertThat(node.get("nationalityType").asString()).isEqualTo("FOREIGN");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("1111111", "1002", "Pass word");
			ArgumentCaptor<SignupRequest> request = ArgumentCaptor.forClass(SignupRequest.class);
			verify(service).signup(request.capture());
			assertThat(request.getValue().identityNumber()).isEqualTo("111111-1111111");
			assertThat(request.getValue().salaryAccount().bankCode()).isEqualTo("020");
		}

		@Test
		@DisplayName("계좌 생략·null 요청도 201")
		void withoutAccount() throws Exception {
			Map<String, Object> omitted = body();
			omitted.remove("salaryAccount");
			Map<String, Object> nulled = body();
			nulled.put("salaryAccount", null);

			assertThat(send(json(omitted)).getStatus()).isEqualTo(201);
			assertThat(send(json(nulled)).getStatus()).isEqualTo(201);
		}

		@Test
		@DisplayName("가입 컨트롤러는 세션·SecurityContext에 새 인증을 저장하지 않는다 (기존 인증 유지)")
		void noLogin() throws Exception {
			var existing = UsernamePasswordAuthenticationToken.authenticated("existing-user", null, List.of());
			SecurityContextHolder.getContext().setAuthentication(existing);
			MockHttpSession session = new MockHttpSession();

			MockHttpServletResponse response = mvc.perform(post("/api/auth/signup").session(session)
				.contentType(MediaType.APPLICATION_JSON).content(json(body()))).andReturn().getResponse();

			assertThat(response.getStatus()).isEqualTo(201);
			assertThat(java.util.Collections.list(session.getAttributeNames())).isEmpty();
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
			assertThat(response.getCookies()).isEmpty();
		}
	}

	@Nested
	@DisplayName("400·415 — 서비스 호출 없음")
	class BadRequest {
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"{", "", "[]", "null", "\"text\""})
		@DisplayName("깨진 JSON·빈 본문·배열·null·문자열 본문")
		void malformed(String raw) throws Exception {
			assertInvalid(send(raw));
		}

		@Test
		@DisplayName("문자 필드의 숫자, 필수값 누락, 공백, 불완전 계좌, 길이 초과")
		void invalidFields() throws Exception {
			Map<String, Object> numericPhone = body();
			numericPhone.put("phone", 1012345678);
			assertInvalid(send(json(numericPhone)));
			Map<String, Object> missing = body();
			missing.remove("identityNumber");
			assertInvalid(send(json(missing)));
			Map<String, Object> blank = body();
			blank.put("name", "  ");
			assertInvalid(send(json(blank)));
			Map<String, Object> partial = body();
			account(partial).remove("accountHolderName");
			assertInvalid(send(json(partial)));
			Map<String, Object> longPassword = body();
			longPassword.put("password", "a".repeat(73));
			assertInvalid(send(json(longPassword)));
		}

		@Test
		// D-1 결정(2026-11-26, 사용자): 415 유지·문서 반영. consumes 불일치는 핸들러 선택 전에 발생해
		// assignableTypes=SignupApi로 제한된 SignupErrorHandler가 적용되지 않고 Spring 기본 처리로 415를 반환한다.
		@DisplayName("JSON이 아닌 Content-Type은 문서대로 415이고 서비스에 도달하지 않으며 입력 원문을 응답하지 않는다")
		void mediaType() throws Exception {
			MockHttpServletResponse response = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("loginId=worker-01&password=Pass word!1")).andReturn().getResponse();

			assertThat(response.getStatus()).isEqualTo(415);
			assertNoSecrets(response);
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("서비스의 SignupException.invalid()도 400")
		void serviceInvalid() throws Exception {
			org.mockito.BDDMockito.willThrow(SignupException.invalid()).given(service).signup(any());

			MockHttpServletResponse response = send(json(body()));
			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(code(response)).isEqualTo("INVALID_REQUEST");
		}

		private void assertInvalid(MockHttpServletResponse response) throws Exception {
			assertThat(response.getStatus()).isEqualTo(400);
			assertThat(code(response)).isEqualTo("INVALID_REQUEST");
			assertNoSecrets(response);
			verifyNoInteractions(service);
		}
	}

	@Nested
	@DisplayName("409·503·500")
	class Errors {
		@Test
		@DisplayName("서비스 중복 오류 두 종류는 409와 각 코드")
		void duplicates() throws Exception {
			org.mockito.BDDMockito.willThrow(SignupException.duplicateLogin()).given(service).signup(any());
			assertError(send(json(body())), 409, "LOGIN_ID_ALREADY_EXISTS");
			org.mockito.BDDMockito.willThrow(SignupException.duplicateIdentity()).given(service).signup(any());
			assertError(send(json(body())), 409, "IDENTITY_ALREADY_EXISTS");
		}

		@ParameterizedTest(name = "{0} → {1}")
		@MethodSource("com.wonday.farm.member.signup.api.SignupApiTest#constraints")
		@DisplayName("동시 가입 저장 실패 모사: 제약 이름으로만 409 변환, 알 수 없는 제약은 중복으로 오판하지 않고 500")
		void constraintNames(String constraint, int status, String code) throws Exception {
			var sql = new SQLException("Duplicate entry 'worker-01' for key '" + constraint + "' insert into member values ('Pass word!1')");
			var violation = new ConstraintViolationException("could not execute statement [insert into member ...]", sql, constraint);
			org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("insert into member failed", violation)).given(service).signup(any());

			assertError(send(json(body())), status, code);
		}

		@Test
		@DisplayName("Hibernate 원인이 없는 무결성 오류와 DuplicateKeyException(원인 없음)은 500, 원인에 uq_member_login이 있으면 409")
		void noHibernateCause() throws Exception {
			org.mockito.BDDMockito.willThrow(new DataIntegrityViolationException("Duplicate entry for key 'uq_member_login'")).given(service).signup(any());
			assertError(send(json(body())), 500, "INTERNAL_SERVER_ERROR");
			org.mockito.BDDMockito.willThrow(new DuplicateKeyException("uq_member_identity_hmac")).given(service).signup(any());
			assertError(send(json(body())), 500, "INTERNAL_SERVER_ERROR");
			org.mockito.BDDMockito.willThrow(new DuplicateKeyException("dup",
				new ConstraintViolationException("x", new SQLException("x"), "member.uq_member_login"))).given(service).signup(any());
			assertError(send(json(body())), 409, "LOGIN_ID_ALREADY_EXISTS");
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.wonday.farm.member.signup.api.SignupApiTest#unavailable")
		@DisplayName("암호화 설정 오류·일시적 DB 장애는 503 SERVICE_UNAVAILABLE")
		void serviceUnavailable(String label, RuntimeException failure) throws Exception {
			org.mockito.BDDMockito.willThrow(failure).given(service).signup(any());

			assertError(send(json(body())), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("그 밖의 내부 예외는 500 INTERNAL_SERVER_ERROR, 원문 비노출")
		void unexpected() throws Exception {
			org.mockito.BDDMockito.willThrow(new IllegalStateException("jdbc:mysql://x password=Pass word!1")).given(service).signup(any());

			assertError(send(json(body())), 500, "INTERNAL_SERVER_ERROR");
		}

		private void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
			assertThat(response.getStatus()).isEqualTo(status);
			assertThat(code(response)).isEqualTo(code);
			JsonNode node = JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
			assertThat(node.propertyNames()).containsExactly("code", "message");
			assertNoSecrets(response);
		}
	}

	static Stream<Arguments> constraints() {
		return Stream.of(
			Arguments.of("uq_member_login", 409, "LOGIN_ID_ALREADY_EXISTS"),
			Arguments.of("member.uq_member_login", 409, "LOGIN_ID_ALREADY_EXISTS"),
			Arguments.of("`member`.`UQ_MEMBER_LOGIN`", 409, "LOGIN_ID_ALREADY_EXISTS"),
			Arguments.of("uq_member_identity_hmac", 409, "IDENTITY_ALREADY_EXISTS"),
			Arguments.of("member.uq_member_identity_hmac", 409, "IDENTITY_ALREADY_EXISTS"),
			Arguments.of("PRIMARY", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("member_salary_account.PRIMARY", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("fk_salary_account_member", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("ck_member_identity_storage", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("uq_member_login_backup", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of("old_uq_member_identity_hmac", 500, "INTERNAL_SERVER_ERROR"),
			Arguments.of(null, 500, "INTERNAL_SERVER_ERROR"));
	}

	static Stream<Arguments> unavailable() {
		return Stream.of(
			Arguments.of("식별정보 키", new IdentityCryptoUnavailableException()),
			Arguments.of("계좌 키", new SalaryAccountCryptoUnavailableException()),
			Arguments.of("DB 연결", new DataAccessResourceFailureException("jdbc:mysql://secret")),
			Arguments.of("쿼리 시간 초과", new QueryTimeoutException("insert into member")),
			Arguments.of("트랜잭션 생성 실패", new CannotCreateTransactionException("jdbc")),
			Arguments.of("트랜잭션 시간 초과", new TransactionTimedOutException("SQL timeout")));
	}

	private MockHttpServletResponse send(String content) throws Exception {
		return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
	}

	private static String code(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").asString();
	}

	private static void assertNoSecrets(MockHttpServletResponse response) throws Exception {
		String raw = response.getContentAsString(StandardCharsets.UTF_8);
		for (String secret : SECRETS) assertThat(raw).as(secret).doesNotContain(secret);
	}
}

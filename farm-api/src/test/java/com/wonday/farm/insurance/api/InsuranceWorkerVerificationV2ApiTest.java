package com.wonday.farm.insurance.api;

import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.JSON;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.SECRETS;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.body;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.json;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.exception.InsuranceErrorHandler;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.insurance.service.WorkerVerificationV2Service;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.JsonNode;

// standalone MockMvc: 실제 InsuranceWorkerVerificationV2Api + 실제 InsuranceErrorHandler + 기본 메시지 변환기·Bean Validation.
// Security 필터 체인(ServiceTokenFilter·InsuranceSecurityConfig)은 없다. 따라서 Bearer 인증·401/403·체인 분리·
// 필터가 붙이는 오류 응답 no-store는 여기서 검증하지 않는다 (통합 검증 대상).
// 서비스는 두 가지로 쓴다: (1) mock 서비스로 컨트롤러·오류 변환만, (2) 실제 서비스 + Repository mock + 테스트 키로 연결 확인.
@DisplayName("보험 V2 내부 API 컨트롤러 (standalone MockMvc, Security 필터 없음)")
class InsuranceWorkerVerificationV2ApiTest {
	private static final String URL = "/api/internal/insurance/v2/worker-verifications";

	private final WorkerVerificationV2Service service = mock(WorkerVerificationV2Service.class);
	private LocalValidatorFactoryBean validator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = build(service);
		given(service.verify(any())).willReturn(new VerifiedWorkerV2Response(101L, "TEST WORKER", "01012345678", NationalityType.FOREIGN));
	}

	private MockMvc build(WorkerVerificationV2Service target) {
		return MockMvcBuilders.standaloneSetup(new InsuranceWorkerVerificationV2Api(target))
			.setControllerAdvice(new InsuranceErrorHandler()).setValidator(validator).build();
	}

	private MockHttpServletResponse send(String content) throws Exception {
		return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoSecrets(MockHttpServletResponse response) throws Exception {
		String text = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(text).doesNotContain(SECRETS);
		assertThat(text).doesNotContain("TEST WORKER\",\"identity");
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		JsonNode node = parse(response);
		assertThat(node.propertyNames()).containsExactly("code", "message");
		assertThat(node.get("code").asString()).isEqualTo(code);
		assertNoSecrets(response);
	}

	@Nested
	@DisplayName("정상 200")
	class Ok {
		@Test
		@DisplayName("정확한 POST 경로, 요청 원문을 서비스에 전달, 200·no-store·JSON 네 필드, workerId는 숫자")
		void ok() throws Exception {
			MockHttpServletResponse response = send(json(body()));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentType()).startsWith("application/json");
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "name", "phone", "nationalityType");
			assertThat(node.get("workerId").isIntegralNumber()).isTrue();
			assertThat(node.get("workerId").asLong()).isEqualTo(101L);
			assertThat(node.get("nationalityType").isString()).isTrue();
			assertThat(node.get("nationalityType").asString()).isEqualTo("FOREIGN");
			assertThat(node.get("phone").asString()).isEqualTo("01012345678");
			assertNoSecrets(response);

			ArgumentCaptor<WorkerVerificationV2Request> captor = ArgumentCaptor.forClass(WorkerVerificationV2Request.class);
			verify(service).verify(captor.capture());
			assertThat(captor.getValue()).isEqualTo(new WorkerVerificationV2Request("TEST WORKER", "010-1234-5678", "FOREIGN", "900101-5000000"));
		}

		@Test
		@DisplayName("DOMESTIC 응답은 문자열 DOMESTIC")
		void domestic() throws Exception {
			given(service.verify(any())).willReturn(new VerifiedWorkerV2Response(7L, "A", "0101234567", NationalityType.DOMESTIC));
			Map<String, Object> request = body();
			request.put("nationalityType", "DOMESTIC");
			assertThat(parse(send(json(request))).get("nationalityType").asString()).isEqualTo("DOMESTIC");
		}

		@Test
		@DisplayName("V1 경로·GET·하위 경로는 이 컨트롤러가 처리하지 않는다 (404/405), 서비스 미호출")
		void otherPaths() throws Exception {
			assertThat(mvc.perform(post("/api/internal/insurance/worker-verifications").contentType(MediaType.APPLICATION_JSON)
				.content(json(body()))).andReturn().getResponse().getStatus()).isEqualTo(404);
			assertThat(mvc.perform(get(URL)).andReturn().getResponse().getStatus()).isEqualTo(405);
			assertThat(mvc.perform(post(URL + "/1").contentType(MediaType.APPLICATION_JSON).content(json(body())))
				.andReturn().getResponse().getStatus()).isEqualTo(404);
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("실제 서비스 연결: 정규화 값·서버 결정 번호 종류·HMAC으로 V2 조회만 하고 응답에 저장 행을 낸다")
		void realServiceWiring() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			IdentityCrypto crypto = IdentityTestKeys.crypto();
			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willReturn(List.of(match(101L, "TEST WORKER", "01012345678", "FOREIGN")));
			mvc = build(new WorkerVerificationV2Service(workers, crypto));

			MockHttpServletResponse response = send(json(body()));

			assertThat(response.getStatus()).isEqualTo(200);
			verify(workers).findByNationalityAndIdentityHmac("TEST WORKER", "01012345678", "FOREIGN", "FOREIGN_REGISTRATION_NUMBER",
				crypto.blindIndex("9001015000000"));
			org.mockito.Mockito.verifyNoMoreInteractions(workers);
			assertNoSecrets(response);
		}
	}

	@Nested
	@DisplayName("입력 오류 400 — 서비스 호출 없음")
	class BadRequest {
		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"{", "", "[]", "null", "\"text\"", "{\"name\":\"A\"", "1"})
		@DisplayName("깨진 JSON·빈 본문·배열·null·문자열·숫자 본문")
		void malformed(String raw) throws Exception {
			assertError(send(raw), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		static Stream<Arguments> invalidBodies() {
			return Stream.of(
				Arguments.of("이름 숫자", "name", 1),
				Arguments.of("전화 숫자", "phone", 1012345678L),
				Arguments.of("국적 boolean", "nationalityType", true),
				Arguments.of("번호 숫자(앞자리 0 손실 위험)", "identityNumber", 9001015000000L),
				Arguments.of("번호 배열", "identityNumber", List.of("900101", "5000000")),
				Arguments.of("번호 객체", "identityNumber", Map.of("value", "9001015000000")),
				Arguments.of("이름 null", "name", null),
				Arguments.of("이름 공백", "name", "   "),
				Arguments.of("전화 빈 값", "phone", ""),
				Arguments.of("국적 소문자", "nationalityType", "foreign"),
				Arguments.of("국적 기타", "nationalityType", "KOREAN"),
				Arguments.of("이름 101자", "name", "A".repeat(101)),
				Arguments.of("번호 101자", "identityNumber", "1".repeat(101)));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidBodies")
		@DisplayName("DTO 검증 실패는 400 INVALID_REQUEST, 입력 원문 비노출, 서비스 미호출")
		void invalidField(String label, String field, Object value) throws Exception {
			Map<String, Object> request = body();
			request.put(field, value);
			assertError(send(json(request)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("필드 누락 각각 400")
		void missing() throws Exception {
			for (String field : new String[] {"name", "phone", "nationalityType", "identityNumber"}) {
				Map<String, Object> request = body();
				request.remove(field);
				assertError(send(json(request)), 400, "INVALID_REQUEST");
			}
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("V1 형식(residentRegistrationNumber만) 요청은 400, 서비스 미호출")
		void v1Body() throws Exception {
			String v1 = "{\"name\":\"TEST WORKER\",\"phone\":\"010-1234-5678\",\"residentRegistrationNumber\":\"111111-1111111\"}";
			assertError(send(v1), 400, "INVALID_REQUEST");
			Map<String, Object> request = body();
			request.remove("identityNumber");
			request.put("residentRegistrationNumber", "111111-1111111");
			assertError(send(json(request)), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("DTO는 통과하지만 서비스에서 형식 오류(12자리 번호)면 실제 서비스가 400, 조회 없음")
		void serviceValidation() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			mvc = build(new WorkerVerificationV2Service(workers, IdentityTestKeys.crypto()));
			Map<String, Object> request = body();
			request.put("identityNumber", "000000000000");
			assertError(send(json(request)), 400, "INVALID_REQUEST");
			Map<String, Object> unicodeBlank = body();
			unicodeBlank.put("name", "　");
			assertError(send(json(unicodeBlank)), 400, "INVALID_REQUEST");
			verifyNoInteractions(workers);
		}

		@Test
		// 관찰 O-1: consumes 불일치는 핸들러 선택 전에 발생해 basePackageClasses 범위의 InsuranceErrorHandler가 적용되지 않는다.
		// InsuranceErrorHandler에 HttpMediaTypeNotSupportedException → 400 선언이 있지만 실제로는 Spring 기본 415다.
		// V2 문서는 Content-Type 오류의 상태를 따로 정하지 않았다 (signup D-1과 같은 동작).
		@DisplayName("JSON이 아닌 Content-Type은 Spring 기본 415이고 서비스 미호출, 입력 원문 비노출")
		void mediaType() throws Exception {
			MockHttpServletResponse response = mvc.perform(post(URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("name=TEST+WORKER&identityNumber=900101-5000000")).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(415);
			assertNoSecrets(response);
			verifyNoInteractions(service);
		}
	}

	@Nested
	@DisplayName("업무·장애 오류 변환")
	class Errors {
		@Test
		@DisplayName("서비스 404 WORKER_IDENTITY_NOT_MATCHED")
		void notMatched() throws Exception {
			willThrow(new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED", "입력한 정보와 일치하는 근로자를 확인할 수 없습니다."))
				.given(service).verify(any());
			assertError(send(json(body())), 404, "WORKER_IDENTITY_NOT_MATCHED");
		}

		@Test
		@DisplayName("실제 서비스 0건·2건·키 미설정은 404·409·503 (Repository mock)")
		void realServiceOutcomes() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			mvc = build(new WorkerVerificationV2Service(workers, IdentityTestKeys.crypto()));
			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willReturn(List.of());
			assertError(send(json(body())), 404, "WORKER_IDENTITY_NOT_MATCHED");

			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willReturn(List.of(match(101L, "TEST WORKER", "01012345678", "FOREIGN"), match(102L, "TEST WORKER", "01012345678", "FOREIGN")));
			MockHttpServletResponse ambiguous = send(json(body()));
			assertError(ambiguous, 409, "WORKER_IDENTITY_AMBIGUOUS");
			assertThat(ambiguous.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("101", "102");

			WorkerVerificationRepository untouched = mock(WorkerVerificationRepository.class);
			mvc = build(new WorkerVerificationV2Service(untouched, new IdentityCrypto("", "")));
			assertError(send(json(body())), 503, "SERVICE_UNAVAILABLE");
			verifyNoInteractions(untouched);
		}

		static Stream<Arguments> unavailable() {
			return Stream.of(
				Arguments.of(InsuranceApiException.unavailable()),
				Arguments.of(new DataAccessResourceFailureException("Communications link failure jdbc:mysql://db 900101-5000000")),
				Arguments.of(new QueryTimeoutException("select * from member where identity_hmac=")),
				Arguments.of(new CannotAcquireLockException("SQL lock")),
				Arguments.of(new CannotCreateTransactionException("Exception jdbc")),
				Arguments.of(new TransactionTimedOutException("timeout 900101-5000000")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unavailable")
		@DisplayName("키·일시적 DB·트랜잭션 장애는 503 SERVICE_UNAVAILABLE, 예외 메시지 비노출")
		void serviceUnavailable(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).verify(any());
			assertError(send(json(body())), 503, "SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("서비스 409 WORKER_IDENTITY_AMBIGUOUS")
		void ambiguous() throws Exception {
			willThrow(new InsuranceApiException(HttpStatus.CONFLICT, "WORKER_IDENTITY_AMBIGUOUS", "입력한 정보로 근로자를 한 명으로 확인할 수 없습니다."))
				.given(service).verify(any());
			assertError(send(json(body())), 409, "WORKER_IDENTITY_AMBIGUOUS");
		}

		static Stream<Arguments> unexpected() {
			return Stream.of(
				Arguments.of(new IllegalStateException("900101-5000000 hmac=abc")),
				Arguments.of(new BadSqlGrammarException("select", "select * from member where identity_hmac = ?",
					new java.sql.SQLException("SQL syntax 900101-5000000"))),
				Arguments.of(new NullPointerException("ciphertext v1:abc")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unexpected")
		@DisplayName("기타 예외(SQL 문법·NPE 등)는 500 INTERNAL_SERVER_ERROR, 내부 메시지 비노출")
		void internal(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).verify(any());
			assertError(send(json(body())), 500, "INTERNAL_SERVER_ERROR");
		}
	}
}

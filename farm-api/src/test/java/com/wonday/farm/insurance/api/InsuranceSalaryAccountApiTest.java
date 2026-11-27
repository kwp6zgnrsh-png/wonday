package com.wonday.farm.insurance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.exception.InsuranceErrorHandler;
import com.wonday.farm.insurance.repository.WorkerSalaryAccountRepository;
import com.wonday.farm.insurance.service.WorkerSalaryAccountQueryService;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 InsuranceSalaryAccountApi + 실제 InsuranceErrorHandler + 기본 메시지 변환기.
// ServiceTokenFilter·InsuranceSecurityConfig 체인이 없으므로 Bearer 인증·401/403·세션 격리, 그리고
// 필터가 붙이는 오류 응답의 Cache-Control: no-store는 여기서 검증하지 않는다 (통합 검증 대상).
@DisplayName("보험 서버용 급여 계좌 조회 API (standalone MockMvc, Security 필터 없음)")
class InsuranceSalaryAccountApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String URL = "/api/internal/insurance/workers/{id}/salary-account";
	private static final List<String> SECRETS = List.of("SQL", "select ", "jdbc", "Exception", "ciphertext", "account_number",
		"1002-0030-0400", "100200300400", "v1:", "maskedAccountNumber", "accountHolderName", "version");

	private final WorkerSalaryAccountQueryService service = mock(WorkerSalaryAccountQueryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = build(service);
	}

	private static MockMvc build(WorkerSalaryAccountQueryService target) {
		return MockMvcBuilders.standaloneSetup(new InsuranceSalaryAccountApi(target)).setControllerAdvice(new InsuranceErrorHandler()).build();
	}

	private static WorkerSalaryAccountRepository.AccountStatus row(long workerId, String role, Long accountMemberId, String bankCode) {
		return new WorkerSalaryAccountRepository.AccountStatus() {
			@Override public long getWorkerId() { return workerId; }
			@Override public String getMemberRole() { return role; }
			@Override public Long getAccountMemberId() { return accountMemberId; }
			@Override public String getBankCode() { return bankCode; }
		};
	}

	private MockHttpServletResponse get(Object id) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL, id)).andReturn().getResponse();
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		JsonNode node = parse(response);
		assertThat(node.propertyNames()).containsExactly("code", "message");
		assertThat(node.get("code").asString()).isEqualTo(code);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRETS);
	}

	@Nested
	@DisplayName("정상 200")
	class Ok {
		@Test
		@DisplayName("등록: GET 경로의 workerId를 서비스에 전달, 200·no-store·JSON 세 필드(숫자·boolean·문자열)")
		void registered() throws Exception {
			given(service.find(101L)).willReturn(new WorkerSalaryAccountResponse(101L, true, "020"));
			MockHttpServletResponse response = get(101);

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentType()).startsWith("application/json");
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
			assertThat(node.get("workerId").isIntegralNumber()).isTrue();
			assertThat(node.get("workerId").asLong()).isEqualTo(101L);
			assertThat(node.get("registered").booleanValue()).isTrue();
			assertThat(node.get("bankCode").asString()).isEqualTo("020");
			verify(service).find(101L);
		}

		@Test
		@DisplayName("미등록도 200, bankCode는 JSON null로 포함")
		void unregistered() throws Exception {
			given(service.find(101L)).willReturn(new WorkerSalaryAccountResponse(101L, false, null));
			MockHttpServletResponse response = get(101);
			assertThat(response.getStatus()).isEqualTo(200);
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
			assertThat(node.get("registered").booleanValue()).isFalse();
			assertThat(node.get("bankCode").isNull()).isTrue();
		}

		@Test
		@DisplayName("POST·다른 하위 경로는 이 컨트롤러가 처리하지 않는다 (405/404), 서비스 미호출")
		void otherPaths() throws Exception {
			assertThat(mvc.perform(post(URL, 101)).andReturn().getResponse().getStatus()).isEqualTo(405);
			assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/api/internal/insurance/workers/101/salary-account/history")).andReturn().getResponse().getStatus()).isEqualTo(404);
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("실제 서비스 연결(Repository mock): 등록·미등록·고용주 404·불일치 503")
		void realService() throws Exception {
			WorkerSalaryAccountRepository repository = mock(WorkerSalaryAccountRepository.class);
			mvc = build(new WorkerSalaryAccountQueryService(repository));
			given(repository.findStatus(101L)).willReturn(Optional.of(row(101L, "WORKER", 101L, "004")));
			assertThat(parse(get(101)).get("bankCode").asString()).isEqualTo("004");
			given(repository.findStatus(101L)).willReturn(Optional.of(row(101L, "WORKER", null, null)));
			assertThat(parse(get(101)).get("registered").booleanValue()).isFalse();
			given(repository.findStatus(101L)).willReturn(Optional.of(row(101L, "EMPLOYER", 101L, "004")));
			assertError(get(101), 404, "WORKER_NOT_FOUND");
			given(repository.findStatus(101L)).willReturn(Optional.of(row(101L, "WORKER", 102L, "004")));
			assertError(get(101), 503, "SERVICE_UNAVAILABLE");
			assertError(get(0), 400, "INVALID_REQUEST");
		}
	}

	@Nested
	@DisplayName("400 INVALID_REQUEST")
	class BadRequest {
		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"abc", "1.5", "1e2", "1L", "9223372036854775808", "-9223372036854775809", "0x", "--1"})
		@DisplayName("숫자가 아닌 ID·Long 범위 초과는 경로 변환 단계에서 400, 서비스 미호출")
		void notNumber(String id) throws Exception {
			assertError(get(id), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@ParameterizedTest(name = "[{0}]")
		@ValueSource(longs = {0, -1})
		@DisplayName("0·음수는 서비스의 400을 그대로 변환 (실제 서비스)")
		void nonPositive(long id) throws Exception {
			WorkerSalaryAccountRepository repository = mock(WorkerSalaryAccountRepository.class);
			mvc = build(new WorkerSalaryAccountQueryService(repository));
			assertError(get(id), 400, "INVALID_REQUEST");
			verifyNoInteractions(repository);
		}

		@ParameterizedTest(name = "[{0}] → 101")
		@ValueSource(strings = {"0x65", "#65", "+101", "0101", "１０１"})
		// 관찰 O-1 (현재 동작 기록, 계약 판단은 작성자 몫): Spring 기본 String→long 변환(NumberUtils.parseNumber)은
		// 16진수(0x·#)·부호·유니코드 숫자를 받아 같은 숫자 ID로 바꾼다. 서비스에는 101이 전달된다.
		@DisplayName("관찰: 16진수·+부호·앞자리 0·전각 숫자 ID는 400이 아니라 숫자 101로 변환된다")
		void lenientConversion(String id) throws Exception {
			given(service.find(101L)).willReturn(new WorkerSalaryAccountResponse(101L, false, null));
			assertThat(get(id).getStatus()).isEqualTo(200);
			verify(service).find(101L);
		}
	}

	@Nested
	@DisplayName("404·503·500")
	class Errors {
		@Test
		@DisplayName("서비스 404 WORKER_NOT_FOUND")
		void notFound() throws Exception {
			willThrow(new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND", "해당 근로자를 찾을 수 없습니다."))
				.given(service).find(anyLong());
			assertError(get(101), 404, "WORKER_NOT_FOUND");
		}

		static Stream<Arguments> unavailable() {
			return Stream.of(
				Arguments.of(InsuranceApiException.unavailable()),
				Arguments.of(new DataAccessResourceFailureException("Communications link failure jdbc")),
				Arguments.of(new QueryTimeoutException("select account.bank_code from member_salary_account")),
				Arguments.of(new CannotAcquireLockException("SQL lock")),
				Arguments.of(new CannotCreateTransactionException("Exception jdbc")),
				Arguments.of(new TransactionTimedOutException("timeout")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unavailable")
		@DisplayName("비정상 결과·일시적 DB·트랜잭션 장애는 503, 예외 원문 비노출")
		void serviceUnavailable(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).find(anyLong());
			assertError(get(101), 503, "SERVICE_UNAVAILABLE");
		}

		static Stream<Arguments> unexpected() {
			return Stream.of(
				Arguments.of(new BadSqlGrammarException("select", "select account_number_ciphertext from member_salary_account",
					new java.sql.SQLException("SQL syntax 100200300400"))),
				Arguments.of(new IllegalStateException("v1:abc 1002-0030-0400")),
				Arguments.of(new NullPointerException("ciphertext")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unexpected")
		@DisplayName("기타 예외는 500, SQL·계좌·암호문 문자열 비노출")
		void internal(RuntimeException exception) throws Exception {
			willThrow(exception).given(service).find(anyLong());
			assertError(get(101), 500, "INTERNAL_SERVER_ERROR");
		}

		@Test
		@DisplayName("오류 응답의 no-store는 이 테스트 범위 밖: InsuranceErrorHandler는 Cache-Control을 붙이지 않는다 (ServiceTokenFilter 담당, 미검증)")
		void errorCacheHeaderNotSetByHandler() throws Exception {
			willThrow(InsuranceApiException.unavailable()).given(service).find(anyLong());
			assertThat(get(101).getHeader("Cache-Control")).isNull();
		}
	}
}

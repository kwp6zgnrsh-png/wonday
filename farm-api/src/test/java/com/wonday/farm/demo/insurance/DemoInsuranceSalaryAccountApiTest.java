package com.wonday.farm.demo.insurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerSalaryAccountRepository;
import com.wonday.farm.insurance.service.WorkerSalaryAccountQueryService;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoInsuranceSalaryAccountApi(컨트롤러 내부 @ExceptionHandler 포함), 조회 서비스는 mock 또는
// 실제 서비스 + Repository mock. principal은 SecurityContext에 직접 넣는다.
// - "principal 없음 → 403 DEMO_ACCESS_DENIED"는 컨트롤러 자체 방어만 본다. 실제 익명 요청은 Security 필터에서
//   먼저 401이 될 것으로 설정돼 있으며(HttpStatusEntryPoint), 이는 이 테스트에서 검증하지 않는다.
// - @Profile 선언 확인은 실제 프로필별 빈 등록 검증이 아니다.
@DisplayName("farm-demo 보험용 계좌 상태 본인 조회 (standalone MockMvc, principal 직접 주입)")
class DemoInsuranceSalaryAccountApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String URL = "/demo/api/insurance/salary-account";
	private static final long ID = 101L;
	private static final List<String> SECRETS = List.of("SQL", "select ", "jdbc", "Exception", "ciphertext", "1002-0030-0400",
		"100200300400", "v1:", "Bearer", "service-token", "test-service-token", "maskedAccountNumber", "accountHolderName", "version");

	private final WorkerSalaryAccountQueryService service = mock(WorkerSalaryAccountQueryService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = build(service);
		signIn(ID, MemberRole.WORKER);
		given(service.find(ID)).willReturn(new WorkerSalaryAccountResponse(ID, true, "020"));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	private static MockMvc build(WorkerSalaryAccountQueryService target) {
		return MockMvcBuilders.standaloneSetup(new DemoInsuranceSalaryAccountApi(target))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
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

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertError(MockHttpServletResponse response, int status, String code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(status);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode node = parse(response);
		assertThat(node.propertyNames()).containsExactly("code", "message");
		assertThat(node.get("code").asString()).isEqualTo(code);
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRETS);
	}

	private static WorkerSalaryAccountRepository.AccountStatus row(long workerId, String role, Long accountMemberId, String bankCode) {
		return new WorkerSalaryAccountRepository.AccountStatus() {
			@Override public long getWorkerId() { return workerId; }
			@Override public String getMemberRole() { return role; }
			@Override public Long getAccountMemberId() { return accountMemberId; }
			@Override public String getBankCode() { return bankCode; }
		};
	}

	@Nested
	@DisplayName("선언·화면")
	class Declarations {
		@Test
		@DisplayName("@Profile(\"farm-demo\")·화면/조회 경로·produces 선언 (실제 프로필별 빈 등록은 미검증), 의존성은 조회 서비스 하나")
		void declarations() throws Exception {
			assertThat(DemoInsuranceSalaryAccountApi.class.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
			GetMapping page = DemoInsuranceSalaryAccountApi.class.getDeclaredMethod("page").getAnnotation(GetMapping.class);
			assertThat(page.value()).containsExactly("/demo/insurance-salary-account");
			assertThat(page.produces()).containsExactly("text/html;charset=UTF-8");
			GetMapping find = DemoInsuranceSalaryAccountApi.class.getDeclaredMethod("findMine", FarmPrincipal.class).getAnnotation(GetMapping.class);
			assertThat(find.value()).containsExactly(URL);
			assertThat(find.produces()).containsExactly("application/json;charset=UTF-8");
			// 서버 토큰 설정·암호화 키·계좌 관리 서비스(SalaryAccountService·SalaryAccountCrypto) 의존 없음
			assertThat(Arrays.stream(DemoInsuranceSalaryAccountApi.class.getDeclaredFields()).<Class<?>>map(f -> f.getType()).toList())
				.containsExactly(WorkerSalaryAccountQueryService.class);
			assertThat(DemoInsuranceSalaryAccountApi.class.getConstructors()[0].getParameterTypes())
				.containsExactly(WorkerSalaryAccountQueryService.class);
		}

		@Test
		@DisplayName("GET 화면: 실제 farm-insurance-salary-account.html을 200·text/html·no-store로 반환")
		void page() throws Exception {
			MockHttpServletResponse response = perform(get("/demo/insurance-salary-account"));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentType()).startsWith("text/html");
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>보험용 급여 계좌 조회</h1>");
		}
	}

	@Nested
	@DisplayName("본인 조회 200")
	class Ok {
		@Test
		@DisplayName("근로자 principal ID로만 서비스 호출, 200·no-store·세 필드")
		void ok() throws Exception {
			MockHttpServletResponse response = perform(get(URL));
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
			assertThat(node.get("workerId").asLong()).isEqualTo(ID);
			verify(service).find(ID);
			verifyNoMoreInteractions(service);
		}

		@Test
		@DisplayName("쿼리에 다른 workerId·memberId를 넣어도 principal ID로만 조회")
		void ignoresRequestId() throws Exception {
			assertThat(perform(get(URL).param("workerId", "202").param("memberId", "202")).getStatus()).isEqualTo(200);
			verify(service).find(ID);
			verifyNoMoreInteractions(service);
		}

		@Test
		@DisplayName("실제 조회 서비스 연결(Repository mock): 등록 → 다른 은행 → 해제 순서로 응답이 바뀐다 (실제 DB 반영 검증 아님)")
		void realServiceSequence() throws Exception {
			WorkerSalaryAccountRepository repository = mock(WorkerSalaryAccountRepository.class);
			mvc = build(new WorkerSalaryAccountQueryService(repository));
			given(repository.findStatus(ID)).willReturn(Optional.of(row(ID, "WORKER", ID, "020")));
			assertThat(parse(perform(get(URL))).get("bankCode").asString()).isEqualTo("020");
			given(repository.findStatus(ID)).willReturn(Optional.of(row(ID, "WORKER", ID, "088")));
			assertThat(parse(perform(get(URL))).get("bankCode").asString()).isEqualTo("088");
			given(repository.findStatus(ID)).willReturn(Optional.of(row(ID, "WORKER", null, null)));
			JsonNode removed = parse(perform(get(URL)));
			assertThat(removed.get("registered").booleanValue()).isFalse();
			assertThat(removed.get("bankCode").isNull()).isTrue();
		}
	}

	@Nested
	@DisplayName("거절·오류 — no-store, 비노출")
	class Errors {
		@Test
		@DisplayName("컨트롤러 방어: principal 없음·고용주·ID 0·음수는 403 DEMO_ACCESS_DENIED, 서비스 미호출 (실제 익명 401과 별개)")
		void rejectedPrincipal() throws Exception {
			for (Runnable login : new Runnable[] {SecurityContextHolder::clearContext, () -> signIn(ID, MemberRole.EMPLOYER),
					() -> signIn(0, MemberRole.WORKER), () -> signIn(-1, MemberRole.WORKER)}) {
				login.run();
				assertError(perform(get(URL)), 403, "DEMO_ACCESS_DENIED");
			}
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("서비스가 다른 workerId를 반환하면 결과를 노출하지 않고 503")
		void otherWorkerResult() throws Exception {
			given(service.find(ID)).willReturn(new WorkerSalaryAccountResponse(202L, true, "088"));
			MockHttpServletResponse response = perform(get(URL));
			assertError(response, 503, "SERVICE_UNAVAILABLE");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("202", "088");
		}

		@Test
		@DisplayName("로그인 뒤 DB 역할 변경·계정 삭제: 실제 서비스가 404 WORKER_NOT_FOUND, 컨트롤러가 그대로 변환")
		void roleChangedAfterLogin() throws Exception {
			WorkerSalaryAccountRepository repository = mock(WorkerSalaryAccountRepository.class);
			mvc = build(new WorkerSalaryAccountQueryService(repository));
			given(repository.findStatus(ID)).willReturn(Optional.of(row(ID, "EMPLOYER", ID, "020")));
			MockHttpServletResponse employer = perform(get(URL));
			assertError(employer, 404, "WORKER_NOT_FOUND");
			assertThat(employer.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("020");
			given(repository.findStatus(ID)).willReturn(Optional.empty());
			assertError(perform(get(URL)), 404, "WORKER_NOT_FOUND");
		}

		static Stream<Arguments> serviceErrors() {
			return Stream.of(
				Arguments.of(new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND", "해당 근로자를 찾을 수 없습니다."), 404, "WORKER_NOT_FOUND"),
				Arguments.of(InsuranceApiException.unavailable(), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new DataAccessResourceFailureException("Communications link failure jdbc"), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new QueryTimeoutException("select bank_code"), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotCreateTransactionException("Exception"), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new BadSqlGrammarException("select", "select account_number_ciphertext", new java.sql.SQLException("SQL")), 500,
					"INTERNAL_SERVER_ERROR"),
				Arguments.of(new IllegalStateException("v1:abc 100200300400"), 500, "INTERNAL_SERVER_ERROR"));
		}

		@ParameterizedTest(name = "{2} ← {0}")
		@MethodSource("serviceErrors")
		@DisplayName("서비스 예외 변환: 404·503·일시적 DB 503·기타 500, 원문 비노출")
		void serviceError(RuntimeException exception, int status, String code) throws Exception {
			willThrow(exception).given(service).find(anyLong());
			assertError(perform(get(URL)), status, code);
		}
	}
}

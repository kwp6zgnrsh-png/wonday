package com.wonday.farm.demo.insurance;

import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.JSON;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.SECRETS;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.body;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.json;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.match;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.insurance.service.WorkerVerificationV2Service;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import tools.jackson.databind.JsonNode;

// standalone MockMvc: 실제 DemoWorkerVerificationV2Api(컨트롤러 내부 @ExceptionHandler 포함). 서비스·MemberRepository는 mock,
// 일부 테스트는 실제 V2 서비스 + Repository mock + 테스트 키.
// principal은 SecurityContext에 직접 넣는다(AuthenticationPrincipalArgumentResolver). 따라서 실제 로그인·세션·CSRF·
// SecurityConfig의 hasRole("WORKER") 차단은 검증하지 않는다. @Profile·@Transactional은 선언만 확인하며 실제 프로필별 빈 등록과
// 트랜잭션 동작은 검증하지 않는다 (통합 검증 대상).
@DisplayName("farm-demo 보험 V2 본인 확인 API (standalone MockMvc, principal 직접 주입)")
class DemoWorkerVerificationV2ApiTest {
	private static final String URL = "/demo/api/insurance/v2/worker-verifications";
	private static final long WORKER_ID = 101L;

	private final WorkerVerificationV2Service service = mock(WorkerVerificationV2Service.class);
	private final MemberRepository members = mock(MemberRepository.class);
	private LocalValidatorFactoryBean validator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		validator = new LocalValidatorFactoryBean();
		validator.afterPropertiesSet();
		mvc = build(service);
		signIn(WORKER_ID, MemberRole.WORKER);
		given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, "WORKER")));
		given(service.verify(any())).willReturn(own());
	}

	@AfterEach
	void tearDown() {
		validator.close();
		SecurityContextHolder.clearContext();
	}

	private MockMvc build(WorkerVerificationV2Service target) {
		return MockMvcBuilders.standaloneSetup(new DemoWorkerVerificationV2Api(target, members)).setValidator(validator)
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
	}

	private static VerifiedWorkerV2Response own() {
		return new VerifiedWorkerV2Response(WORKER_ID, "TEST WORKER", "01012345678", NationalityType.FOREIGN);
	}

	private static void signIn(long id, MemberRole role) {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(id);
		given(principal.role()).willReturn(role);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	private static Member member(long id, String role) {
		var member = new Member("member-" + id, "hash", "회원 " + id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "role", role);
		return member;
	}

	private MockHttpServletResponse send(String content) throws Exception {
		return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse();
	}

	private static JsonNode parse(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
	}

	private static void assertNoSecrets(MockHttpServletResponse response) throws Exception {
		assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(SECRETS).doesNotContain("Bearer", "csrf", "_csrf");
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
	@DisplayName("선언·화면 (프로필·트랜잭션은 선언만)")
	class Declarations {
		@Test
		@DisplayName("@Profile(\"farm-demo\") 선언, GET 화면·POST API 경로·consumes/produces 선언 (실제 프로필별 등록은 미검증)")
		void mappings() throws Exception {
			assertThat(DemoWorkerVerificationV2Api.class.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
			GetMapping page = DemoWorkerVerificationV2Api.class.getDeclaredMethod("page").getAnnotation(GetMapping.class);
			assertThat(page.value()).containsExactly("/demo/worker-verification");
			assertThat(page.produces()).containsExactly("text/html;charset=UTF-8");
			Method verify = DemoWorkerVerificationV2Api.class.getDeclaredMethod("verify", FarmPrincipal.class, WorkerVerificationV2Request.class);
			PostMapping post = verify.getAnnotation(PostMapping.class);
			assertThat(post.value()).containsExactly(URL);
			assertThat(post.consumes()).containsExactly("application/json");
			assertThat(post.produces()).containsExactly("application/json;charset=UTF-8");
			assertThat(verify.getAnnotation(Transactional.class).readOnly()).isTrue();
		}

		@Test
		@DisplayName("GET 화면은 실제 farm-worker-verification.html을 200·text/html·no-store로 반환한다")
		void page() throws Exception {
			MockHttpServletResponse response = mvc.perform(get("/demo/worker-verification")).andReturn().getResponse();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentType()).startsWith("text/html");
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>보험 V2 근로자 확인</h1>");
		}

		@Test
		@DisplayName("의존성은 V2 서비스·MemberRepository뿐이다 (Repository·IdentityCrypto로 대조 로직을 복제하지 않음)")
		void dependencies() {
			List<Class<?>> types = Arrays.stream(DemoWorkerVerificationV2Api.class.getDeclaredFields()).<Class<?>>map(f -> f.getType()).toList();
			assertThat(types).containsExactlyInAnyOrder(WorkerVerificationV2Service.class, MemberRepository.class);
		}
	}

	@Nested
	@DisplayName("정상 200 — 본인 근로자")
	class Ok {
		@Test
		@DisplayName("본인 workerId와 일치하면 200·no-store, 네 필드만, 서비스에 요청 원문 전달, DB 역할 재확인")
		void ok() throws Exception {
			MockHttpServletResponse response = send(json(body()));

			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
			JsonNode node = parse(response);
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "name", "phone", "nationalityType");
			assertThat(node.get("workerId").asLong()).isEqualTo(WORKER_ID);
			assertNoSecrets(response);
			verify(members).findById(WORKER_ID);
			verify(service).verify(new WorkerVerificationV2Request("TEST WORKER", "010-1234-5678", "FOREIGN", "900101-5000000"));
			verifyNoMoreInteractions(service);
		}

		@Test
		@DisplayName("실제 V2 서비스 연결: 본인 행이면 200, V2 조회만 호출")
		void realService() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willReturn(List.of(match(WORKER_ID, "TEST WORKER", "01012345678", "FOREIGN")));
			mvc = build(new WorkerVerificationV2Service(workers, IdentityTestKeys.crypto()));

			assertThat(send(json(body())).getStatus()).isEqualTo(200);
			verify(workers).findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString());
			verifyNoMoreInteractions(workers);
		}
	}

	@Nested
	@DisplayName("본인 범위 — 다른 근로자 404")
	class Ownership {
		@Test
		@DisplayName("서비스가 다른 근로자를 반환하면 404 WORKER_IDENTITY_NOT_MATCHED, 상대 ID·이름·전화 비노출")
		void otherWorker() throws Exception {
			given(service.verify(any())).willReturn(new VerifiedWorkerV2Response(202L, "OTHER WORKER", "01099998888", NationalityType.FOREIGN));

			MockHttpServletResponse response = send(json(body()));
			assertError(response, 404, "WORKER_IDENTITY_NOT_MATCHED");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("202", "OTHER WORKER", "01099998888");
		}

		@Test
		@DisplayName("실제 V2 서비스 연결: 다른 근로자 정보가 정확히 일치해도 404, 상대 정보 비노출")
		void realServiceOtherWorker() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willReturn(List.of(match(202L, "OTHER WORKER", "01099998888", "FOREIGN")));
			mvc = build(new WorkerVerificationV2Service(workers, IdentityTestKeys.crypto()));

			MockHttpServletResponse response = send(json(body()));
			assertError(response, 404, "WORKER_IDENTITY_NOT_MATCHED");
			assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("202", "OTHER WORKER", "01099998888");
		}

		@Test
		@DisplayName("본인 불일치 404와 서비스 0건 404의 응답 본문이 같다 (존재 여부 구분 불가)")
		void sameAsNotFound() throws Exception {
			given(service.verify(any())).willReturn(new VerifiedWorkerV2Response(202L, "OTHER", "01099998888", NationalityType.FOREIGN));
			String other = send(json(body())).getContentAsString(StandardCharsets.UTF_8);
			willThrow(new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED", "입력한 정보와 일치하는 근로자를 확인할 수 없습니다."))
				.given(service).verify(any());
			String none = send(json(body())).getContentAsString(StandardCharsets.UTF_8);
			assertThat(other).isEqualTo(none);
		}
	}

	@Nested
	@DisplayName("접근 거절 403 — 서비스 호출 없음")
	class Denied {
		@Test
		@DisplayName("principal 없음: 403 DEMO_ACCESS_DENIED, DB·서비스 미호출")
		void noPrincipal() throws Exception {
			SecurityContextHolder.clearContext();
			assertError(send(json(body())), 403, "DEMO_ACCESS_DENIED");
			verifyNoInteractions(members, service);
		}

		@Test
		@DisplayName("세션 역할이 고용주: 403, DB·서비스 미호출")
		void employerPrincipal() throws Exception {
			signIn(WORKER_ID, MemberRole.EMPLOYER);
			assertError(send(json(body())), 403, "DEMO_ACCESS_DENIED");
			verifyNoInteractions(members, service);
		}

		@Test
		@DisplayName("로그인 후 DB 계정 삭제: 403, 서비스 미호출")
		void deleted() throws Exception {
			given(members.findById(WORKER_ID)).willReturn(Optional.empty());
			assertError(send(json(body())), 403, "DEMO_ACCESS_DENIED");
			verifyNoInteractions(service);
		}

		static Stream<Arguments> changedRoles() {
			return Stream.of(Arguments.of("EMPLOYER"), Arguments.of("ADMIN"), Arguments.of("worker"), Arguments.of((Object) null));
		}

		@ParameterizedTest(name = "DB 역할 [{0}]")
		@MethodSource("changedRoles")
		@DisplayName("로그인 후 DB 역할 변경·해석 불가: 403, 서비스 미호출")
		void roleChanged(String role) throws Exception {
			given(members.findById(WORKER_ID)).willReturn(Optional.of(member(WORKER_ID, role)));
			assertError(send(json(body())), 403, "DEMO_ACCESS_DENIED");
			verifyNoInteractions(service);
		}
	}

	@Nested
	@DisplayName("입력·업무·장애 오류 — no-store, 비노출")
	class Errors {
		@Test
		@DisplayName("DTO 검증 실패(12자리는 서비스 몫, 숫자 번호·국적 오류·누락은 DTO)는 400, 서비스 미호출")
		void invalid() throws Exception {
			Map<String, Object> numeric = body();
			numeric.put("identityNumber", 9001015000000L);
			assertError(send(json(numeric)), 400, "INVALID_REQUEST");
			Map<String, Object> nationality = body();
			nationality.put("nationalityType", "foreign");
			assertError(send(json(nationality)), 400, "INVALID_REQUEST");
			Map<String, Object> missing = body();
			missing.remove("identityNumber");
			assertError(send(json(missing)), 400, "INVALID_REQUEST");
			assertError(send("{"), 400, "INVALID_REQUEST");
			verifyNoInteractions(service);
		}

		@Test
		@DisplayName("화면의 입력 오류 예시(식별번호 12자리)는 실제 서비스에서 400, 조회 없음")
		void twelveDigits() throws Exception {
			WorkerVerificationRepository workers = mock(WorkerVerificationRepository.class);
			mvc = build(new WorkerVerificationV2Service(workers, IdentityTestKeys.crypto()));
			Map<String, Object> request = body();
			request.put("identityNumber", "000000000000");
			assertError(send(json(request)), 400, "INVALID_REQUEST");
			verifyNoInteractions(workers);
		}

		static Stream<Arguments> serviceErrors() {
			return Stream.of(
				Arguments.of(InsuranceApiException.invalidRequest(), 400, "INVALID_REQUEST"),
				Arguments.of(new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED", "입력한 정보와 일치하는 근로자를 확인할 수 없습니다."), 404, "WORKER_IDENTITY_NOT_MATCHED"),
				Arguments.of(new InsuranceApiException(HttpStatus.CONFLICT, "WORKER_IDENTITY_AMBIGUOUS", "입력한 정보로 근로자를 한 명으로 확인할 수 없습니다."), 409, "WORKER_IDENTITY_AMBIGUOUS"),
				Arguments.of(InsuranceApiException.unavailable(), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new QueryTimeoutException("select identity_hmac 900101-5000000"), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new CannotCreateTransactionException("jdbc Exception"), 503, "SERVICE_UNAVAILABLE"),
				Arguments.of(new BadSqlGrammarException("select", "select * from member", new java.sql.SQLException("SQL 900101-5000000")), 500, "INTERNAL_SERVER_ERROR"),
				Arguments.of(new IllegalStateException("hmac 900101-5000000"), 500, "INTERNAL_SERVER_ERROR"));
		}

		@ParameterizedTest(name = "{2}")
		@MethodSource("serviceErrors")
		@DisplayName("서비스 예외의 상태·코드 변환과 no-store, 내부 메시지 비노출")
		void serviceError(RuntimeException exception, int status, String code) throws Exception {
			willThrow(exception).given(service).verify(any());
			assertError(send(json(body())), status, code);
		}

		@Test
		@DisplayName("회원 재확인 DB 장애는 503, 기타 DB 오류는 500, 서비스 미호출")
		void memberLookupFailure() throws Exception {
			willThrow(new DataAccessResourceFailureException("Communications link failure jdbc")).given(members).findById(anyLong());
			assertError(send(json(body())), 503, "SERVICE_UNAVAILABLE");
			willThrow(new BadSqlGrammarException("select", "select * from member", new java.sql.SQLException("SQL"))).given(members).findById(anyLong());
			assertError(send(json(body())), 500, "INTERNAL_SERVER_ERROR");
			verifyNoInteractions(service);
		}
	}
}

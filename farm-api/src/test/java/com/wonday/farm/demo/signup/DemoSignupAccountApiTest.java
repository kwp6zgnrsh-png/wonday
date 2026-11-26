package com.wonday.farm.demo.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.auth.FarmPrincipal;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.signup.testsupport.SignupTestData;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// standalone MockMvc: 실제 DemoSignupAccountApi(컨트롤러 내부 @ExceptionHandler 포함). Repository는 mock.
// principal은 SecurityContext에 직접 넣는다. SecurityConfig의 hasAnyRole 제한·farm-demo 프로필 빈 등록은 검증하지 않는다.
@DisplayName("farm-demo 본인 계좌 등록 여부 조회 (/demo/api/signup/salary-account)")
class DemoSignupAccountApiTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final long MEMBER_ID = 101L;

	private final MemberRepository members = mock(MemberRepository.class);
	private final MemberSalaryAccountRepository accounts = mock(MemberSalaryAccountRepository.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DemoSignupAccountApi(members, accounts))
		.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
	private String ciphertext;

	@BeforeEach
	void signedIn() {
		FarmPrincipal principal = mock(FarmPrincipal.class);
		given(principal.memberId()).willReturn(MEMBER_ID);
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		given(members.findById(MEMBER_ID)).willReturn(Optional.of(member("WORKER")));
		ciphertext = new SalaryAccountCrypto(SignupTestData.ACCOUNT_KEY).encrypt(MEMBER_ID, "020", "100200300400");
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("등록: principal ID로만 조회해 memberId·registered=true·은행 코드·예금주만 반환, 암호문·번호·키 비노출, no-store")
	void registered() throws Exception {
		given(accounts.findById(MEMBER_ID)).willReturn(Optional.of(
			new MemberSalaryAccount(MEMBER_ID, "020", ciphertext, "TEST WORKER", LocalDateTime.parse("2026-11-26T01:02:03.123456"))));

		MockHttpServletResponse response = perform(get("/demo/api/signup/salary-account").param("memberId", "999"));

		assertThat(response.getStatus()).isEqualTo(200);
		assertNoStore(response);
		JsonNode body = body(response);
		assertThat(body.propertyNames()).containsExactly("memberId", "registered", "bankCode", "accountHolderName");
		assertThat(body.get("memberId").asLong()).isEqualTo(MEMBER_ID);
		assertThat(body.get("registered").asBoolean()).isTrue();
		assertThat(body.get("bankCode").asString()).isEqualTo("020");
		assertThat(body.get("accountHolderName").asString()).isEqualTo("TEST WORKER");
		assertThat(raw(response)).doesNotContain(ciphertext, "100200300400", "v1:", SignupTestData.ACCOUNT_KEY);
		verify(members).findById(MEMBER_ID);
		verify(accounts).findById(MEMBER_ID);
		verifyNoMoreInteractions(members, accounts);
	}

	@Test
	@DisplayName("미등록: registered=false, bankCode·accountHolderName은 명시적 null (EMPLOYER도 허용)")
	void notRegistered() throws Exception {
		given(members.findById(MEMBER_ID)).willReturn(Optional.of(member("EMPLOYER")));
		given(accounts.findById(MEMBER_ID)).willReturn(Optional.empty());

		JsonNode body = body(perform(get("/demo/api/signup/salary-account")));

		assertThat(body.get("registered").asBoolean()).isFalse();
		assertThat(body.get("bankCode").isNull()).isTrue();
		assertThat(body.get("accountHolderName").isNull()).isTrue();
		assertThat(body.get("memberId").asLong()).isEqualTo(MEMBER_ID);
	}

	@Test
	@DisplayName("삭제된 계정·해석 불가 역할은 403 DEMO_ACCESS_DENIED이고 계좌를 조회하지 않는다")
	void deniedAccounts() throws Exception {
		for (Optional<Member> member : List.of(Optional.<Member>empty(), Optional.of(member("ADMIN")), Optional.of(member("worker")))) {
			given(members.findById(MEMBER_ID)).willReturn(member);
			MockHttpServletResponse response = perform(get("/demo/api/signup/salary-account"));
			assertThat(response.getStatus()).isEqualTo(403);
			assertThat(body(response).get("code").asString()).isEqualTo("DEMO_ACCESS_DENIED");
			assertNoStore(response);
		}
		verifyNoInteractions(accounts);
	}

	@Test
	@DisplayName("DB 일시 장애 503, 그 밖의 예외 500 — 상태·코드만, 원문 비노출, no-store")
	void failures() throws Exception {
		for (RuntimeException failure : List.of(new DataAccessResourceFailureException("jdbc:mysql://secret"),
				new QueryTimeoutException("select account_number_ciphertext"), new CannotCreateTransactionException("tx"))) {
			org.mockito.BDDMockito.willThrow(failure).given(accounts).findById(anyLong());
			MockHttpServletResponse response = perform(get("/demo/api/signup/salary-account"));
			assertThat(response.getStatus()).isEqualTo(503);
			assertThat(body(response).get("code").asString()).isEqualTo("SERVICE_UNAVAILABLE");
			assertNoStore(response);
			assertThat(raw(response)).doesNotContain("jdbc", "select", "secret");
		}
		org.mockito.BDDMockito.willThrow(new IllegalStateException("v1:" + ciphertext)).given(accounts).findById(anyLong());
		MockHttpServletResponse response = perform(get("/demo/api/signup/salary-account"));
		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(body(response).get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
		assertNoStore(response);
		assertThat(raw(response)).doesNotContain(ciphertext);
	}

	@Test
	@DisplayName("@Profile(\"farm-demo\") 선언과 읽기 전용 트랜잭션 선언 (실제 프로필 등록은 미검증)")
	void declarations() throws Exception {
		assertThat(DemoSignupAccountApi.class.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
		var method = DemoSignupAccountApi.class.getMethod("account", FarmPrincipal.class);
		assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
	}

	private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
		return mvc.perform(request).andReturn().getResponse();
	}

	private static String raw(MockHttpServletResponse response) throws Exception {
		return response.getContentAsString(StandardCharsets.UTF_8);
	}

	private static JsonNode body(MockHttpServletResponse response) throws Exception {
		return JSON.readTree(raw(response));
	}

	private static void assertNoStore(MockHttpServletResponse response) {
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
	}

	private static Member member(String role) {
		Member member = new Member("member", "{bcrypt}hash", "회원", MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		ReflectionTestUtils.setField(member, "role", role);
		return member;
	}
}

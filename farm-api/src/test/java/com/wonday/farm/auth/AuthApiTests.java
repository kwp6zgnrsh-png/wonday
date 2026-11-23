package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
// 테스트용 회원 행은 테스트가 끝나면 롤백한다.
@Transactional
class AuthApiTests {
	private static final String DISPLAY_NAME = "테스트 농업 계정";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private MemberRepository members;

	@Autowired
	private PasswordEncoder encoder;

	@Test
	void registeredAccountsCanSwitchRolesWithSeparateSessions() throws Exception {
		Member employer = register(MemberRole.EMPLOYER, "employer-integration-test");
		Member worker = register(MemberRole.WORKER, "worker-integration-test");
		// 미인증 본인 조회와 CSRF 토큰이 없는 로그인 요청은 각각 401과 403을 반환한다.
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
		mvc.perform(login(employer.getLoginId(), "employer-integration-test")).andExpect(status().isForbidden());

		CsrfRequest anonymous = csrf(new MockHttpSession());
		mvc.perform(anonymous.authorize(login(employer.getLoginId(), "wrong-password")))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/me").session(anonymous.session())).andExpect(status().isUnauthorized());

		// 로그인에 성공하면 세션 ID가 바뀌고 고용주 본인의 정보가 반환된다.
		String anonymousSessionId = anonymous.session().getId();
		MvcResult loginResult = mvc.perform(anonymous.authorize(login(employer.getLoginId(), "employer-integration-test")))
			.andExpect(status().isNoContent()).andReturn();
		MockHttpSession employerSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertThat(employerSession.getId()).isNotEqualTo(anonymousSessionId);
		assertOwnMember(employerSession, employer.getId(), MemberRole.EMPLOYER);

		// 로그인 전에 받은 토큰은 사용할 수 없고, 새 토큰으로 로그아웃하면 세션이 무효화된다.
		mvc.perform(anonymous.authorize(post("/api/auth/logout"))).andExpect(status().isForbidden());
		mvc.perform(csrf(employerSession).authorize(post("/api/auth/logout")))
			.andExpect(status().isNoContent());
		assertThat(employerSession.isInvalid()).isTrue();
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

		MvcResult workerLogin = mvc.perform(csrf(new MockHttpSession())
				.authorize(login(worker.getLoginId(), "worker-integration-test")))
			.andExpect(status().isNoContent()).andReturn();
		assertOwnMember((MockHttpSession) workerLogin.getRequest().getSession(false), worker.getId(), MemberRole.WORKER);
	}

	@Test
	void demoPageRequiresDemoProfile() throws Exception {
		mvc.perform(get("/demo/login")).andExpect(status().isUnauthorized());
	}

	private Member register(MemberRole role, String password) {
		String loginId = "auth-test-" + UUID.randomUUID();
		return members.save(new Member(loginId, encoder.encode(password), DISPLAY_NAME, role));
	}

	private void assertOwnMember(MockHttpSession session, long memberId, MemberRole role) throws Exception {
		// 다른 회원 ID를 요청에 넣어도 세션 본인의 정보만 반환되는지 확인한다.
		mvc.perform(get("/api/auth/me").session(session).param("id", "-1"))
			.andExpect(status().isOk())
			.andExpect(content().encoding(StandardCharsets.UTF_8))
			.andExpect(jsonPath("$.id").value(equalTo(memberId), Long.class))
			.andExpect(jsonPath("$.role").value(role.name()))
			.andExpect(jsonPath("$.name").value(DISPLAY_NAME))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	private static MockHttpServletRequestBuilder login(String loginId, String password) {
		return post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.formField("loginId", loginId).formField("password", password);
	}

	private CsrfRequest csrf(MockHttpSession session) throws Exception {
		String response = mvc.perform(get("/api/auth/csrf").session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new CsrfRequest(session, JsonPath.read(response, "$.headerName"), JsonPath.read(response, "$.token"));
	}

	private record CsrfRequest(MockHttpSession session, String header, String token) {
		MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request) {
			return request.session(session).header(header, token).with(mockRequest -> {
				mockRequest.setRequestedSessionId(session.getId());
				mockRequest.setRequestedSessionIdValid(true);
				return mockRequest;
			});
		}
	}
}

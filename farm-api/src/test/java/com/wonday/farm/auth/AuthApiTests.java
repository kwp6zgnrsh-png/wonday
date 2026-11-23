package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

// 인증 API를 기능별로 검증한다. 각 테스트는 독립적으로 계정을 만들고, 회원 행은 테스트가 끝나면 롤백한다.
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthApiTests {
	private static final String DISPLAY_NAME = "테스트 농업 계정";
	private static final String PASSWORD = "correct-password";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private MemberRepository members;
	@Autowired
	private PasswordEncoder encoder;

	@Nested
	class CsrfToken {
		@Test
		void issuesHeaderNameAndTokenWithoutLogin() throws Exception {
			mvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value(not(emptyOrNullString())))
				.andExpect(jsonPath("$.token").value(not(emptyOrNullString())));
		}
	}

	@Nested
	class Login {
		@Test
		void withoutCsrfTokenReturns403() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);

			mvc.perform(login(employer.getLoginId(), PASSWORD)).andExpect(status().isForbidden());
		}

		@Test
		void withWrongPasswordReturns401AndStaysAnonymous() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);
			CsrfRequest anonymous = csrf(new MockHttpSession());

			mvc.perform(anonymous.authorize(login(employer.getLoginId(), "wrong-password")))
				.andExpect(status().isUnauthorized());
			mvc.perform(get("/api/auth/me").session(anonymous.session())).andExpect(status().isUnauthorized());
		}

		@Test
		void withUnknownLoginIdReturns401() throws Exception {
			CsrfRequest anonymous = csrf(new MockHttpSession());

			mvc.perform(anonymous.authorize(login("no-such-" + UUID.randomUUID(), PASSWORD)))
				.andExpect(status().isUnauthorized());
		}

		@Test
		void withUnsupportedRoleCodeReturns401() throws Exception {
			// DB에 허용되지 않은 역할 코드가 들어 있으면 비밀번호가 맞아도 로그인할 수 없다.
			Member admin = register(MemberRole.EMPLOYER);
			ReflectionTestUtils.setField(admin, "role", "ADMIN");
			members.saveAndFlush(admin);
			CsrfRequest anonymous = csrf(new MockHttpSession());

			mvc.perform(anonymous.authorize(login(admin.getLoginId(), PASSWORD)))
				.andExpect(status().isUnauthorized());
		}

		@Test
		void successReturns204AndRotatesSessionId() throws Exception {
			// 세션 고정 공격을 막기 위해 로그인 전 세션 ID가 로그인 후에 바뀌어야 한다.
			Member employer = register(MemberRole.EMPLOYER);
			CsrfRequest anonymous = csrf(new MockHttpSession());
			String anonymousSessionId = anonymous.session().getId();

			MockHttpSession loggedIn = loginSession(anonymous, employer);

			assertThat(loggedIn.getId()).isNotEqualTo(anonymousSessionId);
		}
	}

	@Nested
	class Me {
		@Test
		void withoutSessionReturns401() throws Exception {
			mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
		}

		@Test
		void employerGetsOwnIdNameAndRole() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);

			assertOwnMember(loginSession(csrf(new MockHttpSession()), employer), employer, MemberRole.EMPLOYER);
		}

		@Test
		void workerGetsOwnIdNameAndRole() throws Exception {
			Member worker = register(MemberRole.WORKER);

			assertOwnMember(loginSession(csrf(new MockHttpSession()), worker), worker, MemberRole.WORKER);
		}

		@Test
		void ignoresMemberIdInRequestAndNeverExposesPasswordHash() throws Exception {
			// 요청에 다른 회원 ID를 넣어도 세션 본인의 정보만 반환한다.
			Member employer = register(MemberRole.EMPLOYER);
			Member other = register(MemberRole.WORKER);
			MockHttpSession session = loginSession(csrf(new MockHttpSession()), employer);

			mvc.perform(get("/api/auth/me").session(session).param("id", String.valueOf(other.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(equalTo(employer.getId()), Long.class))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.loginId").doesNotExist());
		}

		@Test
		void accountsLoggedInAtSameTimeKeepSeparateSessions() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);
			Member worker = register(MemberRole.WORKER);

			MockHttpSession employerSession = loginSession(csrf(new MockHttpSession()), employer);
			MockHttpSession workerSession = loginSession(csrf(new MockHttpSession()), worker);

			assertOwnMember(employerSession, employer, MemberRole.EMPLOYER);
			assertOwnMember(workerSession, worker, MemberRole.WORKER);
		}
	}

	@Nested
	class Logout {
		@Test
		void withFreshCsrfTokenReturns204AndInvalidatesSession() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);
			MockHttpSession session = loginSession(csrf(new MockHttpSession()), employer);

			mvc.perform(csrf(session).authorize(post("/api/auth/logout"))).andExpect(status().isNoContent());

			// 폐기된 세션 쿠키를 다시 보내는 검증은 실제 서버를 쓰는 AuthHttpTests.logoutInvalidatesSessionOnServer가 맡는다.
			assertThat(session.isInvalid()).isTrue();
		}

		@Test
		void withCsrfTokenIssuedBeforeLoginReturns403() throws Exception {
			// 로그인하면 CSRF 토큰이 새로 발급되므로 로그인 전 토큰으로는 로그아웃할 수 없다.
			Member employer = register(MemberRole.EMPLOYER);
			CsrfRequest anonymous = csrf(new MockHttpSession());
			MockHttpSession session = loginSession(anonymous, employer);

			mvc.perform(anonymous.authorize(post("/api/auth/logout"))).andExpect(status().isForbidden());
			assertThat(session.isInvalid()).isFalse();
		}
	}

	@Nested
	class AccessControl {
		@Test
		void unlistedEndpointIsDeniedEvenWhenLoggedIn() throws Exception {
			Member employer = register(MemberRole.EMPLOYER);
			MockHttpSession session = loginSession(csrf(new MockHttpSession()), employer);

			mvc.perform(get("/api/admin/members").session(session)).andExpect(status().isForbidden());
		}

		@Test
		void demoPageIsNotServedWithoutDemoProfile() throws Exception {
			mvc.perform(get("/demo/login")).andExpect(status().isUnauthorized());
		}
	}

	private Member register(MemberRole role) {
		return members.save(new Member("auth-test-" + UUID.randomUUID(), encoder.encode(PASSWORD), DISPLAY_NAME, role));
	}

	private MockHttpSession loginSession(CsrfRequest anonymous, Member member) throws Exception {
		return (MockHttpSession) mvc.perform(anonymous.authorize(login(member.getLoginId(), PASSWORD)))
			.andExpect(status().isNoContent())
			.andReturn().getRequest().getSession(false);
	}

	private void assertOwnMember(MockHttpSession session, Member member, MemberRole role) throws Exception {
		mvc.perform(get("/api/auth/me").session(session))
			.andExpect(status().isOk())
			.andExpect(content().encoding(StandardCharsets.UTF_8))
			.andExpect(jsonPath("$.id").value(equalTo(member.getId()), Long.class))
			.andExpect(jsonPath("$.name").value(DISPLAY_NAME))
			.andExpect(jsonPath("$.role").value(role.name()));
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

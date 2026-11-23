package com.wonday.insurance.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberAuthApiTests {

	private static final String PASSWORD = "test-insurance-password";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MemberRepository members;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void workerCanSignUpLogInReadOwnAccountAndLogOut() throws Exception {
		String loginId = newLoginId();
		MockHttpSession anonymousSession = new MockHttpSession();
		CsrfRequest anonymous = csrf(anonymousSession);

		mvc.perform(anonymous.authorize(signup(loginId, PASSWORD)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("보험 가입자"))
			.andExpect(jsonPath("$.role").value("WORKER"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		Member member = members.findByLoginId(loginId).orElseThrow();
		assertThat(member.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, member.getPasswordHash())).isTrue();
		mvc.perform(get("/api/auth/me").session(anonymousSession)).andExpect(status().isUnauthorized());

		mvc.perform(anonymous.authorize(login(loginId, "incorrect-password")))
			.andExpect(status().isUnauthorized());
		String anonymousSessionId = anonymousSession.getId();
		MockHttpSession authenticated = (MockHttpSession) mvc.perform(anonymous.authorize(login(loginId, PASSWORD)))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		assertThat(authenticated.getId()).isNotEqualTo(anonymousSessionId);

		mvc.perform(get("/api/auth/me").session(authenticated).param("id", "-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(member.getId().intValue()))
			.andExpect(jsonPath("$.name").value("보험 가입자"))
			.andExpect(jsonPath("$.role").value("WORKER"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist())
			.andExpect(jsonPath("$.phone").doesNotExist())
			.andExpect(jsonPath("$.residentRegistrationNumber").doesNotExist());

		mvc.perform(anonymous.authorize(post("/api/auth/logout"))).andExpect(status().isForbidden());
		mvc.perform(csrf(authenticated).authorize(post("/api/auth/logout"))).andExpect(status().isNoContent());
		assertThat(authenticated.isInvalid()).isTrue();
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateLoginIdReturnsConflict() throws Exception {
		String loginId = newLoginId();
		members.saveAndFlush(new Member(loginId, passwordEncoder.encode(PASSWORD), "Existing", MemberRole.WORKER));

		mvc.perform(signup(loginId, PASSWORD).with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().isConflict());
	}

	@Test
	void invalidSignupInputReturnsBadRequest() throws Exception {
		mvc.perform(signup(" ", PASSWORD).with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().isBadRequest());
		mvc.perform(signup(newLoginId(), "short").with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().isBadRequest());
		mvc.perform(signup(newLoginId(), "가".repeat(25)).with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void authenticationWritesRequireCsrf() throws Exception {
		String loginId = newLoginId();
		mvc.perform(signup(loginId, PASSWORD)).andExpect(status().isForbidden());
		mvc.perform(login(loginId, PASSWORD)).andExpect(status().isForbidden());
		mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
	}

	@Test
	void existingAdministratorCanLogInAndReadOwnAccount() throws Exception {
		String loginId = newLoginId();
		Member admin = members.saveAndFlush(new Member(loginId, passwordEncoder.encode(PASSWORD), "Admin", MemberRole.ADMIN));
		MockHttpSession authenticated = (MockHttpSession) mvc.perform(csrf(new MockHttpSession())
			.authorize(login(loginId, PASSWORD))).andExpect(status().isNoContent())
			.andReturn().getRequest().getSession(false);

		mvc.perform(get("/api/auth/me").session(authenticated))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(admin.getId().intValue()))
			.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	private CsrfRequest csrf(MockHttpSession session) throws Exception {
		String json = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return new CsrfRequest(session, JsonPath.read(json, "$.headerName"), JsonPath.read(json, "$.token"));
	}

	private static MockHttpServletRequestBuilder signup(String loginId, String password) {
		return post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"loginId":"%s","name":"보험 가입자","password":"%s"}
				""".formatted(loginId, password));
	}

	private static MockHttpServletRequestBuilder login(String loginId, String password) {
		return post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("loginId", loginId).param("password", password);
	}

	private static String newLoginId() {
		return "insurance-test-" + UUID.randomUUID();
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

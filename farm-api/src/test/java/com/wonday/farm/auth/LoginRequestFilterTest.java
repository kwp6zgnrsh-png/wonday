package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoginRequestFilterTest {
	private final LoginRequestFilter filter = new LoginRequestFilter();
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final MockFilterChain chain = new MockFilterChain();

	@Test
	void passesThroughRequestsOtherThanLoginPost() throws Exception {
		MockHttpServletRequest getLogin = new MockHttpServletRequest("GET", LoginRequestFilter.LOGIN_PATH);
		// 로그인 경로가 아니면 쿼리나 Content-Type이 무엇이든 검사하지 않는다.
		getLogin.setQueryString("loginId=x");

		filter.doFilter(getLogin, response, chain);

		assertPassedThrough();
	}

	@Test
	void passesThroughPostToOtherPath() throws Exception {
		MockHttpServletRequest logout = new MockHttpServletRequest("POST", "/api/auth/logout");

		filter.doFilter(logout, response, chain);

		assertPassedThrough();
	}

	@Test
	void rejectsCredentialsInQueryString() throws Exception {
		MockHttpServletRequest request = formLogin("secret");
		request.setQueryString("password=secret");

		filter.doFilter(request, response, chain);

		assertBlockedWith(400);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE, "not a media type"})
	void rejectsNonFormContentType(String contentType) throws Exception {
		MockHttpServletRequest request = loginPost();
		request.setContentType(contentType);

		filter.doFilter(request, response, chain);

		assertBlockedWith(415);
	}

	@Test
	void acceptsFormContentTypeWithCharset() throws Exception {
		MockHttpServletRequest request = formLogin("secret");
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE + ";charset=UTF-8");

		filter.doFilter(request, response, chain);

		assertPassedThrough();
	}

	@Test
	void rejectsPasswordOverBcryptLimitBeforeAuthentication() throws Exception {
		MockHttpServletRequest request = formLogin("a".repeat(PasswordPolicy.BCRYPT_MAX_BYTES + 1));

		filter.doFilter(request, response, chain);

		assertBlockedWith(401);
	}

	@Test
	void passesPasswordAtBcryptLimit() throws Exception {
		filter.doFilter(formLogin("a".repeat(PasswordPolicy.BCRYPT_MAX_BYTES)), response, chain);

		assertPassedThrough();
	}

	@Test
	void leavesMissingPasswordToSpringSecurity() throws Exception {
		MockHttpServletRequest request = loginPost();
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		request.addParameter("loginId", "user");

		filter.doFilter(request, response, chain);

		assertPassedThrough();
	}

	private static MockHttpServletRequest loginPost() {
		return new MockHttpServletRequest("POST", LoginRequestFilter.LOGIN_PATH);
	}

	private static MockHttpServletRequest formLogin(String password) {
		MockHttpServletRequest request = loginPost();
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		request.addParameter("loginId", "user");
		request.addParameter(LoginRequestFilter.PASSWORD_PARAMETER, password);
		return request;
	}

	private void assertPassedThrough() {
		assertThat(chain.getRequest()).as("filter chain called").isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}

	private void assertBlockedWith(int status) {
		assertThat(chain.getRequest()).as("filter chain called").isNull();
		assertThat(response.getStatus()).isEqualTo(status);
	}
}

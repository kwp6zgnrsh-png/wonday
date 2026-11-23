package com.wonday.farm.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

// Spring Security가 자격 증명을 읽기 전에 로그인 요청의 입력 형식을 검사한다.
final class LoginRequestFilter extends OncePerRequestFilter {
	static final String LOGIN_PATH = "/api/auth/login";
	static final String PASSWORD_PARAMETER = "password";

	private final RequestMatcher loginRequest = PathPatternRequestMatcher.withDefaults()
		.matcher(HttpMethod.POST, LOGIN_PATH);

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !loginRequest.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// URL 쿼리에 들어간 값은 읽지 않고, 자격 증명은 폼 본문으로 받는다.
		if (request.getQueryString() != null) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		if (!isFormContentType(request.getContentType())) {
			response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		String password = request.getParameter(PASSWORD_PARAMETER);
		if (password != null && PasswordPolicy.exceedsBcryptLimit(password)) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		chain.doFilter(request, response);
	}

	private static boolean isFormContentType(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(contentType));
		} catch (InvalidMediaTypeException exception) {
			return false;
		}
	}
}

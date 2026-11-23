package com.wonday.insurance.member;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LoginFormRequestFilter extends OncePerRequestFilter {

	private static final PathPatternRequestMatcher LOGIN =
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login");

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !LOGIN.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String query = request.getQueryString();
		if (query != null && !query.isEmpty()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		if (!isForm(request.getContentType())) {
			response.setStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}
		// With no query string, the authentication filter reads parameters from the form body.
		chain.doFilter(request, response);
	}

	private static boolean isForm(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_FORM_URLENCODED.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
		} catch (InvalidMediaTypeException exception) {
			return false;
		}
	}
}

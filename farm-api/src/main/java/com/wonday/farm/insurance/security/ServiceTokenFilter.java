package com.wonday.farm.insurance.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// 브라우저 로그인 세션 대신 보험 서버의 전용 토큰만 인증한다. 토큰 자체는 principal이나 로그에 남기지 않는다.
final class ServiceTokenFilter extends OncePerRequestFilter {

	private final String serviceToken;

	ServiceTokenFilter(String serviceToken) {
		this.serviceToken = serviceToken;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		response.setHeader("Cache-Control", "no-store");
		var headers = request.getHeaders("Authorization");
		String header = headers.hasMoreElements() ? headers.nextElement() : null;
		if (headers.hasMoreElements() || serviceToken.isBlank() || header == null || header.length() > 8192
				|| !header.regionMatches(true, 0, "Bearer ", 0, 7)
				|| !MessageDigest.isEqual(digest(serviceToken), digest(header.substring(7)))) {
			writeError(response, 401, "INVALID_SERVICE_TOKEN", "서버 인증 토큰을 확인해 주세요.");
			return;
		}
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("insurance-service", null,
			List.of(new SimpleGrantedAuthority("INSURANCE_SERVICE"))));
		SecurityContextHolder.setContext(context);
		try {
			chain.doFilter(request, response);
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static byte[] digest(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
	}
}

package com.wonday.farm.auth;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
		// Spring Security 필터에서 요청별 접근 권한과 로그인·로그아웃을 처리한다.
		return http
			.authorizeHttpRequests(auth -> {
				// 서버 내부의 오류 처리를 허용해 CSRF 차단 시 설정된 403 응답을 유지한다.
				auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				// 데모 모드에서는 로그인 전에도 데모 페이지를 열 수 있다.
				if (environment.matchesProfiles("farm-demo")) {
					auth.requestMatchers(HttpMethod.GET, "/demo/login").permitAll();
				}
				// 로그인 전에 토큰을 발급할 수 있게 하고, 본인 조회는 농업 계정 역할로 제한한다.
				auth.requestMatchers(HttpMethod.GET, "/", "/api/auth/csrf").permitAll()
					.requestMatchers(HttpMethod.POST, LoginRequestFilter.LOGIN_PATH).permitAll()
					.requestMatchers(HttpMethod.GET, "/api/auth/me").hasAnyRole("EMPLOYER", "WORKER")
					.anyRequest().denyAll();
			})
			// 인증이 필요한 API를 세션 없이 호출하면 401을 반환한다.
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.requestCache(AbstractHttpConfigurer::disable)
			.addFilterBefore(new LoginRequestFilter(), UsernamePasswordAuthenticationFilter.class)
			// 폼의 loginId와 password를 검증하고, 성공하면 세션에 인증 정보를 저장한다.
			.formLogin(login -> login
				.loginPage(LoginRequestFilter.LOGIN_PATH)
				.loginProcessingUrl(LoginRequestFilter.LOGIN_PATH)
				.usernameParameter("loginId")
				.passwordParameter(LoginRequestFilter.PASSWORD_PARAMETER)
				.successHandler((request, response, authentication) ->
					response.setStatus(HttpStatus.NO_CONTENT.value()))
				.failureHandler((request, response, exception) ->
					response.setStatus(HttpStatus.UNAUTHORIZED.value())))
			// CSRF 토큰을 포함한 POST 요청으로 현재 세션을 종료하고 204를 반환한다.
			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		// 같은 BCrypt 인코더를 계정 저장 시 해시 생성과 로그인 시 비밀번호 검증에 사용한다.
		return new BCryptPasswordEncoder();
	}
}

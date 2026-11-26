package com.wonday.farm.insurance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
public class InsuranceSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain insuranceSecurityFilterChain(HttpSecurity http,
			@Value("${farm.insurance.service-token:}") String token) throws Exception {
		return http.securityMatcher("/api/internal/insurance/**")
			// 서버 토큰만 쓰는 이 경로에 한해 CSRF를 끈다. 농업 앱의 기존 세션·CSRF 설정은 유지한다.
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
			.requestCache(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, "/api/internal/insurance/worker-verifications").hasAuthority("INSURANCE_SERVICE")
				.requestMatchers(HttpMethod.GET, "/api/internal/insurance/workers/*/work-changes",
					"/api/internal/insurance/workers/*/work-assignments/*").hasAuthority("INSURANCE_SERVICE")
				.anyRequest().denyAll())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, exception) -> ServiceTokenFilter.writeError(response,
					401, "INVALID_SERVICE_TOKEN", "서버 인증 토큰을 확인해 주세요."))
				.accessDeniedHandler((request, response, exception) -> ServiceTokenFilter.writeError(response,
					403, "SERVICE_ACCESS_DENIED", "이 API를 호출할 권한이 없습니다.")))
			.addFilterBefore(new ServiceTokenFilter(token), AnonymousAuthenticationFilter.class)
			.build();
	}
}

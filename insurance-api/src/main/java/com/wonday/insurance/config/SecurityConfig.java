package com.wonday.insurance.config;

import com.wonday.insurance.member.LoginFormRequestFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.GET, "/", "/api/auth/csrf", "/api/products", "/api/products/*").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/me").hasAnyRole("WORKER", "ADMIN")
				.requestMatchers(HttpMethod.POST, "/api/contracts").hasRole("WORKER")
				.requestMatchers(HttpMethod.GET, "/api/contracts", "/api/contracts/*").hasRole("WORKER")
				.requestMatchers(HttpMethod.GET, "/api/claims", "/api/claims/*").hasRole("WORKER")
				.anyRequest().denyAll())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.requestCache(AbstractHttpConfigurer::disable)
			.addFilterBefore(new LoginFormRequestFilter(), UsernamePasswordAuthenticationFilter.class)
			.formLogin(login -> login
				.loginPage("/api/auth/login")
				.loginProcessingUrl("/api/auth/login")
				.usernameParameter("loginId")
				.successHandler((request, response, authentication) ->
					response.setStatus(HttpStatus.NO_CONTENT.value()))
				.failureHandler((request, response, exception) ->
					response.setStatus(HttpStatus.UNAUTHORIZED.value())))
			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
			.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

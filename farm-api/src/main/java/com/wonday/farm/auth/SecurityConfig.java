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
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
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
					auth.requestMatchers(HttpMethod.GET, "/demo/login", "/demo/signup", "/demo/jobs", "/demo/worker-jobs", "/demo/job-location", "/demo/salary-account", "/demo/worker-verification", "/demo/insurance-salary-account").permitAll();
					auth.requestMatchers(HttpMethod.GET, "/demo/work-completion").permitAll();
					auth.requestMatchers(HttpMethod.GET, "/demo/job-apply").permitAll();
					auth.requestMatchers(HttpMethod.GET, "/demo/my-applications").permitAll();
					auth.requestMatchers(HttpMethod.GET, "/demo/work-applicants").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/demo/work-selection").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/demo/work-cancellation").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/demo/work-early-end").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/demo/api/work-selection/fixtures").hasAnyRole("EMPLOYER", "WORKER");
                    auth.requestMatchers(HttpMethod.POST, "/demo/api/work-selection/fixtures").hasAnyRole("EMPLOYER", "WORKER");
                    auth.requestMatchers(HttpMethod.POST, "/demo/api/work-early-end/fixtures").hasRole("EMPLOYER");
					auth.requestMatchers(HttpMethod.POST, "/demo/api/work-applicants/fixtures").hasRole("EMPLOYER");
                    auth.requestMatchers(HttpMethod.POST, "/demo/api/work-cancellation/fixtures").hasRole("EMPLOYER");
					auth.requestMatchers(HttpMethod.POST, "/demo/api/my-applications/fixtures").hasRole("WORKER");
					auth.requestMatchers(HttpMethod.GET, "/demo/api/applications/postings/*/history").hasRole("WORKER");
					auth.requestMatchers(HttpMethod.POST, "/demo/api/applications/fixtures").hasRole("WORKER");
					// 완료 확인 목록·상세는 고용주 본인 세션으로만 제공한다.
					auth.requestMatchers(HttpMethod.GET, "/demo/api/employer/work-assignments",
						"/demo/api/employer/work-assignments/*").hasRole("EMPLOYER");
					// 가입 계좌의 실제 저장 여부만 본인 세션으로 확인한다. 기본 프로필에는 열리지 않는다.
					auth.requestMatchers(HttpMethod.GET, "/demo/api/signup/salary-account").hasAnyRole("EMPLOYER", "WORKER");

					// 보험용 계좌 상태는 토큰 없이 근로자 본인 세션으로만 확인한다.
					auth.requestMatchers(HttpMethod.GET, "/demo/api/insurance/salary-account").hasRole("WORKER");

					// 보험 V2 간이 확인은 근로자 세션과 기존 CSRF 보호를 사용한다.
					auth.requestMatchers(HttpMethod.POST, "/demo/api/insurance/v2/worker-verifications").hasRole("WORKER");
					// 약정 확인은 데모 프로필에서만 세션으로 허용한다. 보험 서버 전용 경로는 그대로 둔다.
					auth.requestMatchers(HttpMethod.GET, "/demo/api/work-assignments", "/demo/api/work-assignments/*").hasRole("WORKER");
				}
				// 로그인 전에 토큰을 발급할 수 있게 하고, 본인 조회는 농업 계정 역할로 제한한다.
				auth.requestMatchers(HttpMethod.GET, "/", "/api/auth/csrf").permitAll()
					.requestMatchers(HttpMethod.POST, LoginRequestFilter.LOGIN_PATH).permitAll()
					// 가입 전에도 요청할 수 있지만 기존 CSRF 검사는 그대로 적용한다.
					.requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
					.requestMatchers(HttpMethod.GET, "/api/auth/me").hasAnyRole("EMPLOYER", "WORKER")
					// 조기 종료는 고용주 세션과 기존 CSRF 보호를 사용한다.
					.requestMatchers(HttpMethod.POST, "/api/work-assignments/*/early-end").hasRole("EMPLOYER")
					// 정식 내 지원 조회는 demo 프로필과 무관하게 근로자 본인 세션을 사용한다.
					.requestMatchers(HttpMethod.GET, "/api/me/applications", "/api/me/applications/*",
						"/api/me/applications/*/history").hasRole("WORKER")
					// 급여 계좌는 근로자 본인만 관리한다. 쓰기 요청의 기존 CSRF 검사는 유지한다.
					.requestMatchers(HttpMethod.GET, "/api/me/salary-account").hasRole("WORKER")
					.requestMatchers(HttpMethod.PUT, "/api/me/salary-account").hasRole("WORKER")
					.requestMatchers(HttpMethod.DELETE, "/api/me/salary-account").hasRole("WORKER")
					// 근무 완료는 고용주 세션과 기존 CSRF 검사를 함께 사용한다.
					.requestMatchers(HttpMethod.POST, "/api/work-assignments/*/completion").hasRole("EMPLOYER")
					// 개인별 시작 전 취소도 고용주 세션과 기존 CSRF 보호를 사용한다.
					.requestMatchers(HttpMethod.POST, "/api/work-assignments/*/cancellation").hasRole("EMPLOYER")
					// 날짜별 지원은 근로자 세션과 기존 CSRF 검사를 사용한다.
					.requestMatchers(HttpMethod.POST, "/api/job-postings/*/applications").hasRole("WORKER")
					.requestMatchers(HttpMethod.POST, "/api/job-postings").hasRole("EMPLOYER")
					.requestMatchers(HttpMethod.GET, "/api/job-postings/mine").hasRole("EMPLOYER")
                    // 새 선정과 조건 재확인은 기존 농업 세션·CSRF 보호를 그대로 사용한다.
                    .requestMatchers(HttpMethod.POST, "/api/job-postings/*/schedules/*/applications/*/selection").hasRole("EMPLOYER")
                    .requestMatchers(HttpMethod.POST, "/api/me/applications/*/condition-confirmation").hasRole("WORKER")
					// WORK-01: 지원자 개인정보는 고용주 세션에서만 조회한다.
					.requestMatchers(HttpMethod.GET, "/api/job-postings/*/schedules/*/applications",
						"/api/job-postings/*/schedules/*/applications/*",
						"/api/job-postings/*/schedules/*/applications/*/contact").hasRole("EMPLOYER")
					// /mine의 고용주 규칙을 먼저 적용하고 근로자의 목록·상세 조회를 구분한다.
					.requestMatchers(HttpMethod.GET, "/api/job-postings", "/api/job-postings/*").hasRole("WORKER")
					.requestMatchers(HttpMethod.PUT, "/api/job-postings/*/schedules/*").hasRole("EMPLOYER")
					.requestMatchers(HttpMethod.POST, "/api/job-postings/*/schedules/*/cancel").hasRole("EMPLOYER")
					.anyRequest().denyAll();
			})
			// 인증이 필요한 API를 세션 없이 호출하면 401을 반환한다.
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
				.accessDeniedHandler((request, response, exception) -> {
                    if (request.getServletPath().equals("/demo/api/work-selection/fixtures")
                            && !(exception instanceof CsrfException)) {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setHeader("Cache-Control", "no-store");
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":\"FARM_ROLE_REQUIRED\",\"message\":\"농업 계정으로 로그인해 주세요.\"}");
                    } else if (request.getMethod().equals("POST")
                            && (request.getServletPath().matches("/api/job-postings/[^/]+/schedules/[^/]+/applications/[^/]+/selection")
                                || request.getServletPath().matches("/api/me/applications/[^/]+/condition-confirmation"))
                            && !(exception instanceof CsrfException)) {
                        boolean worker = request.getServletPath().startsWith("/api/me/");
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setHeader("Cache-Control", "no-store");
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write(worker
                            ? "{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 본인만 조건을 재확인할 수 있습니다.\"}"
                            : "{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 본인 공고의 지원자만 선정할 수 있습니다.\"}");
                    } else if (((request.getMethod().equals("GET")
							&& request.getServletPath().matches("/api/job-postings/[^/]+/schedules/[^/]+/applications(/[^/]+(/contact)?)?"))
							|| (request.getMethod().equals("POST") && request.getServletPath().equals("/demo/api/work-applicants/fixtures")))
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 본인 공고의 지원자만 조회할 수 있습니다.\"}");
					} else if (request.getMethod().equals("GET")
							&& request.getServletPath().matches("/api/me/applications(/[^/]+(/history)?)?")
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 본인만 지원 내역을 조회할 수 있습니다.\"}");
					} else if (request.getMethod().equals("POST")
							&& request.getServletPath().equals("/demo/api/my-applications/fixtures")
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 본인만 시연 데이터를 준비할 수 있습니다.\"}");
					} else if (request.getServletPath().equals("/api/me/salary-account") && !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 본인만 급여 계좌를 관리할 수 있습니다.\"}");
					} else if (((request.getMethod().equals("POST")
								&& (request.getServletPath().matches("/api/job-postings/[^/]+/applications")
									|| request.getServletPath().equals("/demo/api/applications/fixtures")))
							|| (request.getMethod().equals("GET")
								&& request.getServletPath().matches("/demo/api/applications/postings/[^/]+/history")))
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 계정만 공고에 지원할 수 있습니다.\"}");
					} else if (request.getMethod().equals("GET")
							&& request.getServletPath().matches("/demo/api/employer/work-assignments(/[^/]+)?")
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 본인 공고의 확정 근무만 조회할 수 있습니다.\"}");
                    } else if ((request.getServletPath().matches("/api/work-assignments/[^/]+/cancellation")
                                || request.getServletPath().equals("/demo/api/work-cancellation/fixtures"))
                            && request.getMethod().equals("POST") && !(exception instanceof CsrfException)) {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setHeader("Cache-Control", "no-store");
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 본인 공고의 확정 근무를 취소할 수 있습니다.\"}");
					} else if (request.getServletPath().matches("/api/work-assignments/[^/]+/completion")
							&& request.getMethod().equals("POST") && !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 근무를 완료할 수 있습니다.\"}");
					} else if (request.getMethod().equals("POST")
							&& (request.getServletPath().matches("/api/work-assignments/[^/]+/early-end")
								|| request.getServletPath().equals("/demo/api/work-early-end/fixtures"))
							&& !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setHeader("Cache-Control", "no-store");
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 본인 공고의 조기 종료를 기록할 수 있습니다.\"}");
					} else if (request.getServletPath().equals("/api/job-postings")
							&& request.getMethod().equals("POST") && !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 공고를 등록할 수 있습니다.\"}");
					} else if (request.getServletPath().equals("/api/job-postings/mine")
							&& request.getMethod().equals("GET")) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 내 공고를 조회할 수 있습니다.\"}");
					} else if (request.getServletPath().matches("/api/job-postings/[^/]+/schedules/[^/]+")
							&& request.getMethod().equals("PUT") && !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 일정을 수정할 수 있습니다.\"}");
					} else if (request.getServletPath().matches("/api/job-postings/[^/]+/schedules/[^/]+/cancel")
							&& request.getMethod().equals("POST") && !(exception instanceof CsrfException)) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"EMPLOYER_ROLE_REQUIRED\",\"message\":\"고용주 계정만 날짜 모집을 취소할 수 있습니다.\"}");
					} else if (request.getMethod().equals("GET") &&
							(request.getServletPath().equals("/api/job-postings")
							|| request.getServletPath().matches("/api/job-postings/[^/]+"))) {
						response.setStatus(HttpStatus.FORBIDDEN.value());
						response.setContentType("application/json;charset=UTF-8");
						response.getWriter().write("{\"code\":\"WORKER_ROLE_REQUIRED\",\"message\":\"근로자 계정만 공고 목록과 상세를 조회할 수 있습니다.\"}");
					} else {
						new AccessDeniedHandlerImpl().handle(request, response, exception);
					}
				}))
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

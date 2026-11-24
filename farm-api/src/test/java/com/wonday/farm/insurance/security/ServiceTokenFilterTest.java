package com.wonday.farm.insurance.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

// 서버 토큰 필터를 서블릿 mock으로 직접 실행한다. 이 테스트는 필터 하나의 동작만 증명한다.
// InsuranceSecurityConfig가 이 필터를 /api/internal/insurance/** 체인에만 연결하는지, 기존 농업 앱
// 세션·CSRF 체인과 분리되는지, 체인의 401/403 핸들러와 헤더 작성 필터가 어떻게 동작하는지는 증명하지 않는다 (통합 검증 대상).
class ServiceTokenFilterTest {
	private static final String TOKEN = "test-service-token-0123456789";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RecordingChain chain = new RecordingChain();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	class ConfiguredToken {
		@ParameterizedTest(name = "configured=\"{0}\"")
		@ValueSource(strings = {"", " ", "\t"})
		void blankConfiguredTokenRejectsEveryRequest(String configured) throws Exception {
			// 설정이 비어 있을 때 "Bearer " 또는 "Bearer <공백>"이 빈 토큰과 일치해 통과하면 안 된다.
			for (String header : List.of("Bearer ", "Bearer " + configured, "Bearer anything")) {
				var response = run(new ServiceTokenFilter(configured), request(header));

				assertUnauthorized(response);
			}
		}
	}

	@Nested
	class AuthorizationHeader {
		@Test
		void missingHeaderIsRejected() throws Exception {
			assertUnauthorized(run(filter(), new MockHttpServletRequest("GET", "/api/internal/insurance/workers/1/work-changes")));
		}

		@ParameterizedTest(name = "Authorization: {0}")
		@ValueSource(strings = {
			"", "Bearer", "Bearer ", TOKEN, "Basic " + TOKEN, "Token " + TOKEN, "Bearer" + TOKEN,
			"Bearer  " + TOKEN, "Bearer " + TOKEN + " ", " Bearer " + TOKEN, "Bearer " + TOKEN + "x",
			"Bearer test-service-token-012345678", "Bearer TEST-SERVICE-TOKEN-0123456789"})
		void malformedOrWrongHeaderIsRejected(String header) throws Exception {
			assertUnauthorized(run(filter(), request(header)));
		}

		@Test
		void duplicateAuthorizationHeadersAreRejectedEvenWhenBothAreValid() throws Exception {
			var request = request("Bearer " + TOKEN);
			request.addHeader("Authorization", "Bearer " + TOKEN);

			assertUnauthorized(run(filter(), request));
		}

		@Test
		void duplicateHeaderWithWrongFirstValueIsRejected() throws Exception {
			var request = request("Bearer wrong");
			request.addHeader("Authorization", "Bearer " + TOKEN);

			assertUnauthorized(run(filter(), request));
		}

		@Test
		void sessionCookieWithoutBearerTokenIsRejected() throws Exception {
			var request = new MockHttpServletRequest("GET", "/api/internal/insurance/workers/1/work-changes");
			request.setCookies(new Cookie("FARMSESSION", "valid-looking-session-id"));

			assertUnauthorized(run(filter(), request));
		}

		@Test
		void headerLongerThan8192CharactersIsRejectedEvenIfItMatchesTheToken() throws Exception {
			String longToken = "a".repeat(8192 - 7 + 1);

			assertUnauthorized(run(new ServiceTokenFilter(longToken), request("Bearer " + longToken)));
		}

		@Test
		void headerOfExactly8192CharactersIsAccepted() throws Exception {
			String longToken = "a".repeat(8192 - 7);

			run(new ServiceTokenFilter(longToken), request("Bearer " + longToken));

			assertThat(chain.calls).hasValue(1);
		}
	}

	@Nested
	class Rejection {
		@Test
		void writes401JsonWithCodeAndMessageAndStopsTheChain() throws Exception {
			var response = run(filter(), request("Bearer wrong-token-value"));

			assertThat(response.getStatus()).isEqualTo(401);
			assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
			var body = JSON.readTree(response.getContentAsString());
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("INVALID_SERVICE_TOKEN");
			assertThat(body.get("message").asString()).isNotBlank();
			assertThat(chain.calls).hasValue(0);
		}

		@Test
		void rejectionDoesNotEchoTheSubmittedOrConfiguredToken() throws Exception {
			var response = run(filter(), request("Bearer submitted-secret-value"));

			assertThat(response.getContentAsString()).doesNotContain("submitted-secret-value").doesNotContain(TOKEN);
		}

		@Test
		void rejectionLeavesNoAuthenticationBehind() throws Exception {
			run(filter(), request("Bearer wrong"));

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		}

		@Test
		void rejectionIsMarkedNoStore() throws Exception {
			assertThat(run(filter(), request("Bearer wrong")).getHeader("Cache-Control")).isEqualTo("no-store");
		}
	}

	@Nested
	class Acceptance {
		@Test
		void validBearerTokenContinuesTheChainOnce() throws Exception {
			var response = run(filter(), request("Bearer " + TOKEN));

			assertThat(chain.calls).hasValue(1);
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(response.getContentAsString()).isEmpty();
		}

		@Test
		void schemeNameIsCaseInsensitive() throws Exception {
			// RFC 7235: 인증 스킴 이름은 대소문자를 구분하지 않는다. 토큰 값은 구분한다 (위 거절 사례 참고).
			run(filter(), request("bearer " + TOKEN));
			run(filter(), request("BEARER " + TOKEN));

			assertThat(chain.calls).hasValue(2);
		}

		@Test
		void setsServicePrincipalWithOnlyInsuranceAuthorityDuringTheChain() throws Exception {
			run(filter(), request("Bearer " + TOKEN));

			Authentication seen = chain.authentications.getFirst();
			assertThat(seen.isAuthenticated()).isTrue();
			assertThat(seen.getPrincipal()).isEqualTo("insurance-service");
			assertThat(seen.getCredentials()).as("토큰을 자격 증명으로 보관하지 않는다").isNull();
			assertThat(seen.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("INSURANCE_SERVICE");
			assertThat(seen.toString()).doesNotContain(TOKEN);
		}

		@Test
		void validTokenWinsOverSessionCookieAndPrincipalIsStillTheService() throws Exception {
			var request = request("Bearer " + TOKEN);
			request.setCookies(new Cookie("FARMSESSION", "employer-session"));

			run(filter(), request);

			assertThat(chain.authentications.getFirst().getPrincipal()).isEqualTo("insurance-service");
		}

		@Test
		void acceptedResponseIsMarkedNoStore() throws Exception {
			assertThat(run(filter(), request("Bearer " + TOKEN)).getHeader("Cache-Control")).isEqualTo("no-store");
		}

		@Test
		void securityContextIsClearedAfterTheRequest() throws Exception {
			run(filter(), request("Bearer " + TOKEN));

			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		}

		@Test
		void securityContextIsClearedWhenTheChainThrows() {
			FilterChain failing = (request, response) -> {
				assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
				throw new ServletException("downstream failure");
			};

			assertThatThrownBy(() -> filter().doFilter(request("Bearer " + TOKEN), new MockHttpServletResponse(), failing))
				.isInstanceOf(ServletException.class).hasMessage("downstream failure");
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		}
	}

	private static ServiceTokenFilter filter() {
		return new ServiceTokenFilter(TOKEN);
	}

	private static MockHttpServletRequest request(String authorization) {
		var request = new MockHttpServletRequest("GET", "/api/internal/insurance/workers/1/work-changes");
		request.addHeader("Authorization", authorization);
		return request;
	}

	private MockHttpServletResponse run(ServiceTokenFilter filter, MockHttpServletRequest request) throws Exception {
		var response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		return response;
	}

	private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("\"INVALID_SERVICE_TOKEN\"");
		assertThat(chain.calls).hasValue(0);
	}

	private static final class RecordingChain implements FilterChain {
		final AtomicInteger calls = new AtomicInteger();
		final List<Authentication> authentications = new ArrayList<>();

		@Override
		public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
			calls.incrementAndGet();
			authentications.add(SecurityContextHolder.getContext().getAuthentication());
		}
	}
}

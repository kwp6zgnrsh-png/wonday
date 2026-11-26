package com.wonday.farm.demo.insurance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 회귀 방지 검사다. HTML 리소스와 보안 설정 소스의 문자열·구조만 본다.
// 브라우저 실행·실제 fetch·DOM 이벤트는 검증하지 않는다(화면 스크립트의 가짜 DOM 실행은 별도 node 테스트, 보고서 참고).
// 보안 설정 검사는 선언 확인일 뿐이며 실제 필터 체인에서 인증 우회 방지·CSRF 차단이 실행 검증된 것이 아니다 (통합 검증 대상).
@DisplayName("farm-demo 보험 V2 화면 리소스와 보안 설정 선언 (정적 검사)")
class WorkerVerificationDemoPageTest {
	private static String page() throws Exception {
		return new ClassPathResource("demo/farm-worker-verification.html").getContentAsString(StandardCharsets.UTF_8);
	}

	private static String script() throws Exception {
		String html = page();
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String body = matcher.group(1);
		assertThat(matcher.find()).as("인라인 스크립트는 하나").isFalse();
		return body;
	}

	private static String source(String relative) throws Exception {
		return Files.readString(Path.of("src/main/java/com/wonday/farm").resolve(relative), StandardCharsets.UTF_8);
	}

	@Nested
	@DisplayName("화면 구성")
	class Structure {
		@Test
		@DisplayName("이름·전화·국적(DOMESTIC/FOREIGN)·식별번호(password, autocomplete off) 입력과 세 확인 버튼")
		void inputsAndButtons() throws Exception {
			String html = page();
			assertThat(html).contains("<input id=\"name\" maxlength=\"100\" required autocomplete=\"off\">");
			assertThat(html).contains("<input id=\"phone\" type=\"tel\" maxlength=\"100\"");
			assertThat(html).contains("<option value=\"DOMESTIC\">", "<option value=\"FOREIGN\">");
			assertThat(Pattern.compile("<option ").matcher(html).results().count()).isEqualTo(2);
			assertThat(html).containsPattern("<input id=\"identity\" type=\"password\" maxlength=\"100\"[^>]*autocomplete=\"off\">");
			assertThat(html).contains("<form id=\"verification-form\" autocomplete=\"off\">");
			assertThat(html).contains("<button type=\"submit\">입력 정보 확인 · 정상 200</button>",
				"<button id=\"mismatch\" type=\"button\">불일치 예시 · 404</button>",
				"<button id=\"invalid\" type=\"button\">입력 오류 예시 · 400</button>");
			assertThat(html).contains("<fieldset id=\"fields\" disabled>");
		}

		@Test
		@DisplayName("로그인 화면에 V2 확인 화면 링크가 있다")
		void loginLink() throws Exception {
			String login = new ClassPathResource("demo/farm-login.html").getContentAsString(StandardCharsets.UTF_8);
			assertThat(login).contains("<a href=\"/demo/worker-verification\" id=\"verification-link\">");
		}
	}

	@Nested
	@DisplayName("스크립트 정적 회귀 방지 (실행 검증 아님)")
	class ScriptGuards {
		@Test
		@DisplayName("innerHTML·outerHTML·insertAdjacentHTML·document.write·eval을 쓰지 않고 결과는 textContent로 넣는다")
		void noHtmlSinks() throws Exception {
			String js = script();
			assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function");
			assertThat(js).contains("result.textContent = JSON.stringify(display, null, 2);", "status.textContent = message;");
		}

		@Test
		@DisplayName("브라우저 저장소·URL·로그·Authorization·내부 보험 경로를 쓰지 않는다")
		void noStorageOrToken() throws Exception {
			String js = script();
			assertThat(js).doesNotContain("localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.",
				"history.", "location", "URLSearchParams", "Authorization", "Bearer", "/api/internal/");
		}

		@Test
		@DisplayName("요청 순서 구조: CSRF 조회(/api/auth/csrf) → 헤더 [csrf.headerName] 포함 POST /demo/api/... (JSON, same-origin, no-store)")
		void csrfBeforePost() throws Exception {
			String js = script();
			int csrf = js.indexOf("fetch('/api/auth/csrf'");
			int post = js.indexOf("fetch('/demo/api/insurance/v2/worker-verifications'");
			assertThat(csrf).isPositive();
			assertThat(post).isGreaterThan(csrf);
			String request = js.substring(post, js.indexOf("});", post));
			assertThat(request).contains("method: 'POST'", "credentials: 'same-origin'", "cache: 'no-store'",
				"headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token }", "body: JSON.stringify(payload)");
			assertThat(js.indexOf("fetch(")).isEqualTo(js.indexOf("fetch('/api/auth/me'"));
			assertThat(Pattern.compile("fetch\\(").matcher(js).results().count()).isEqualTo(3);
		}

		@Test
		@DisplayName("성공 표시 필드는 workerId·name·phone·nationalityType, 오류 표시는 code·message뿐")
		void displayFields() throws Exception {
			String js = script();
			assertThat(js).contains("{ workerId: data.workerId, name: data.name, phone: data.phone, nationalityType: data.nationalityType }");
			assertThat(js).contains("{ code: data?.code || 'HTTP_' + response.status, message: data?.message ||");
		}
	}

	@Nested
	@DisplayName("보안 설정 선언 (실제 필터 체인 실행 검증 아님)")
	class SecurityDeclarations {
		@Test
		@DisplayName("내부 보험 체인: V2 POST 경로가 V1과 함께 INSURANCE_SERVICE 권한 규칙에 선언돼 있고 나머지는 denyAll")
		void insuranceChain() throws Exception {
			String config = source("insurance/security/InsuranceSecurityConfig.java").replaceAll("\\s+", " ");
			assertThat(config).contains(".requestMatchers(HttpMethod.POST, \"/api/internal/insurance/worker-verifications\", "
				+ "\"/api/internal/insurance/v2/worker-verifications\").hasAuthority(\"INSURANCE_SERVICE\")");
			assertThat(config).contains("securityMatcher(\"/api/internal/insurance/**\")", ".anyRequest().denyAll()",
				"addFilterBefore(new ServiceTokenFilter(token)");
			assertThat(config).doesNotContain("permitAll");
		}

		@Test
		@DisplayName("농업 체인: farm-demo 분기 안에서 GET 화면 permitAll, POST demo API는 hasRole(\"WORKER\")")
		void farmChain() throws Exception {
			String config = source("auth/SecurityConfig.java").replaceAll("\\s+", " ");
			int demo = config.indexOf("if (environment.matchesProfiles(\"farm-demo\")) {");
			int end = config.indexOf("}", demo);
			assertThat(demo).isPositive();
			String block = config.substring(demo, end);
			assertThat(block).containsPattern("requestMatchers\\(HttpMethod\\.GET, [^)]*\"/demo/worker-verification\"\\)\\.permitAll\\(\\)");
			assertThat(block).contains("auth.requestMatchers(HttpMethod.POST, \"/demo/api/insurance/v2/worker-verifications\").hasRole(\"WORKER\");");
			assertThat(config.indexOf("/demo/api/insurance/v2/worker-verifications")).isEqualTo(config.lastIndexOf("/demo/api/insurance/v2/worker-verifications"));
			assertThat(config).contains(".anyRequest().denyAll()");
		}

		@Test
		@DisplayName("농업 체인에 CSRF 비활성화·예외 경로(ignoringRequestMatchers) 선언을 추가하지 않았다")
		void noCsrfIgnore() throws Exception {
			String config = source("auth/SecurityConfig.java");
			assertThat(config).doesNotContain("ignoringRequestMatchers", ".csrf(", "csrf(AbstractHttpConfigurer::disable)", "CsrfConfigurer");
			String demo = source("demo/insurance/DemoWorkerVerificationV2Api.java");
			assertThat(demo).doesNotContain("csrf", "Csrf", "ServiceTokenFilter", "Authorization", "service-token");
		}
	}
}

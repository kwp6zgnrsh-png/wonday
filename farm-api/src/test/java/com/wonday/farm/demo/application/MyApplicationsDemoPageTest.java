package com.wonday.farm.demo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사다. 스크립트 동작은 보고서 폴더의 node 가짜 DOM 테스트에서 원문을 실행해 확인한다.
// SecurityConfig 문자열 검사는 선언 확인일 뿐 실제 필터 체인 동작(401·403·CSRF)을 보장하지 않는다.
@DisplayName("farm-demo 내 지원 화면·링크·보안 선언 (정적)")
class MyApplicationsDemoPageTest {
	private static String resource(String name) throws Exception {
		return new ClassPathResource("demo/" + name).getContentAsString(StandardCharsets.UTF_8);
	}
	private static String script(String html) {
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String body = matcher.group(1);
		assertThat(matcher.find()).as("인라인 스크립트 하나").isFalse();
		return body;
	}

	@Test
	@DisplayName("화면 스크립트: 위험한 HTML 삽입·저장소·로그·eval·Authorization 미사용, 조회는 정식 APP-04 GET, 준비만 POST")
	void page() throws Exception {
		String html = resource("farm-my-applications.html");
		String js = script(html);
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization", "workerId", "/demo/api/applications/");
		assertThat(js).contains("'/api/me/applications?'", "'/api/me/applications/' + id", "'/api/me/applications/' + id + '/history?'",
			"const path = '/demo/api/my-applications/fixtures';", "{method:'POST',headers:{[csrf.body.headerName]:csrf.body.token}}");
		assertThat(js.split("method:'POST'", -1)).as("POST 호출은 한 곳").hasSize(2);
		assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\">");
	}

	@Test
	@DisplayName("로그인 화면: 내 지원 링크는 기본 숨김이고 근로자일 때만 표시")
	void loginLink() throws Exception {
		String login = resource("farm-login.html");
		assertThat(login).contains("<a href=\"/demo/my-applications\" id=\"my-applications-link\" hidden>",
			"document.querySelector('#my-applications-link').hidden = account.role !== 'WORKER';");
	}

	@Test
	@DisplayName("지원 화면 머리말에 내 지원 화면 링크, 내 지원 화면에서 지원·로그인 화면으로 이동")
	void crossLinks() throws Exception {
		assertThat(resource("farm-job-apply.html")).contains("<a href=\"/demo/my-applications\">내 지원 내역 · 조건 비교</a>");
		assertThat(resource("farm-my-applications.html")).contains("<a href=\"/demo/login\">", "<a href=\"/demo/job-apply\">");
	}

	@Test
	@DisplayName("SecurityConfig 선언: 화면 permitAll(GET), 준비 POST·정식 GET 3종 hasRole(WORKER), 403 JSON 처리(CSRF 예외 제외)")
	void securityDeclarations() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java"), StandardCharsets.UTF_8);
		assertThat(config).contains(
			"auth.requestMatchers(HttpMethod.GET, \"/demo/my-applications\").permitAll();",
			"auth.requestMatchers(HttpMethod.POST, \"/demo/api/my-applications/fixtures\").hasRole(\"WORKER\");",
			".requestMatchers(HttpMethod.GET, \"/api/me/applications\", \"/api/me/applications/*\",\n\t\t\t\t\t\t\"/api/me/applications/*/history\").hasRole(\"WORKER\")",
			"request.getServletPath().matches(\"/api/me/applications(/[^/]+(/history)?)?\")",
			"request.getServletPath().equals(\"/demo/api/my-applications/fixtures\")",
			"{\\\"code\\\":\\\"WORKER_ROLE_REQUIRED\\\"");
		// demo 전용 규칙은 matchesProfiles("farm-demo") 블록 안(공통 규칙 시작 전)에 있어야 한다: 위치로 확인
		int demoStart = config.indexOf("environment.matchesProfiles(\"farm-demo\")");
		int commonStart = config.indexOf("auth.requestMatchers(HttpMethod.GET, \"/\", \"/api/auth/csrf\")");
		assertThat(demoStart).isPositive();
		for (String route : new String[] {"\"/demo/my-applications\"", "\"/demo/api/my-applications/fixtures\""}) {
			assertThat(config.indexOf(route)).as(route).isGreaterThan(demoStart).isLessThan(commonStart);
		}
		assertThat(config.indexOf("\"/api/me/applications\"")).isGreaterThan(commonStart);
		assertThat(config).doesNotContain("\"/demo/api/my-applications/fixtures\").permitAll()", "csrf.ignoringRequestMatchers(\"/demo/api/my-applications");
		// 정규식이 의도한 경로만 맞는지(선언 문자열을 그대로 사용)
		Pattern path = Pattern.compile("/api/me/applications(/[^/]+(/history)?)?");
		assertThat(path.matcher("/api/me/applications").matches()).isTrue();
		assertThat(path.matcher("/api/me/applications/501").matches()).isTrue();
		assertThat(path.matcher("/api/me/applications/501/history").matches()).isTrue();
		assertThat(path.matcher("/api/me/applications/501/other").matches()).isFalse();
		assertThat(path.matcher("/api/me/applications-x").matches()).isFalse();
	}
}

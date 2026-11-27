package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;

// AuthDemoPage의 급여 계좌 화면 반환과 보안 설정 선언만 본다. 정적 검사이며 브라우저 동작·실제 필터 체인 실행을 검증한 것이 아니다.
// 화면 스크립트 동작은 보고서 폴더의 node 가짜 DOM 테스트(별도 실행)에서 본다.
@DisplayName("farm-demo 급여 계좌 화면 리소스·보안 설정 선언 (정적)")
class SalaryAccountDemoPageTest {
	@Test
	@DisplayName("/demo/salary-account: farm-demo 프로필 선언, 실제 farm-salary-account.html을 200·no-store로 반환 (실제 프로필 등록은 미검증)")
	void page() throws Exception {
		GetMapping mapping = AuthDemoPage.class.getDeclaredMethod("salaryAccountPage").getAnnotation(GetMapping.class);
		assertThat(mapping.value()).containsExactly("/demo/salary-account");
		assertThat(mapping.produces()).containsExactly("text/html;charset=UTF-8");
		assertThat(AuthDemoPage.class.getAnnotation(Profile.class).value()).containsExactly("farm-demo");

		var response = new AuthDemoPage().salaryAccountPage();
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
		Resource body = response.getBody();
		assertThat(((ClassPathResource) body).getPath()).isEqualTo("demo/farm-salary-account.html");
		String html = body.getContentAsString(StandardCharsets.UTF_8);
		assertThat(html).contains("<h1>급여 계좌 관리</h1>", "const endpoint = '/api/me/salary-account';");
		assertThat(html).doesNotContain("/demo/api/", "localStorage", "sessionStorage", "innerHTML", "console.", "Authorization");
	}

	@Test
	@DisplayName("로그인 화면: 급여 계좌 링크는 기본 숨김이고 근로자일 때만 표시하는 구조")
	void loginLink() throws Exception {
		String login = new ClassPathResource("demo/farm-login.html").getContentAsString(StandardCharsets.UTF_8);
		assertThat(login).contains("<a href=\"/demo/salary-account\" id=\"salary-account-link\" hidden>");
		assertThat(login).contains("document.querySelector('#salary-account-link').hidden = account.role !== 'WORKER';");
	}

	@Test
	@DisplayName("SecurityConfig 선언: GET·PUT·DELETE /api/me/salary-account는 hasRole(WORKER), 화면 GET은 farm-demo 분기 permitAll, CSRF 비활성·예외 경로 없음")
	void securityDeclarations() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java")).replaceAll("\\s+", " ");
		for (String method : new String[] {"GET", "PUT", "DELETE"}) {
			assertThat(config).contains(".requestMatchers(HttpMethod." + method + ", \"/api/me/salary-account\").hasRole(\"WORKER\")");
		}
		int demo = config.indexOf("if (environment.matchesProfiles(\"farm-demo\")) {");
		String block = config.substring(demo, config.indexOf("}", demo));
		// 다른 데모 경로가 뒤에 추가되어도 같은 GET permitAll 선언에 포함되는지 확인한다.
		assertThat(block).containsPattern("HttpMethod\\.GET, [^)]*\"/demo/salary-account\"[^)]*\\)\\.permitAll\\(\\)");
		assertThat(config).doesNotContain("ignoringRequestMatchers", ".csrf(");
		assertThat(config).contains(".anyRequest().denyAll()");
	}
}

package com.wonday.farm.demo.insurance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 회귀 방지 검사다. 브라우저 동작은 보고서 폴더의 node 가짜 DOM 테스트(별도 실행)에서 보고,
// 보안 설정은 소스 선언만 확인한다. 실제 토큰 없음·오류·세션 격리·권한 우회 방지는 실행 검증하지 않았다.
@DisplayName("farm-demo 보험용 계좌 화면·보안 설정 선언 (정적)")
class InsuranceSalaryAccountDemoPageTest {
	private static String page() throws Exception {
		return new ClassPathResource("demo/farm-insurance-salary-account.html").getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("화면: 토큰·회원 ID 입력 칸 없음, 관리 화면 새 창 링크, 데모 경로만 호출, 내부 API·Authorization·저장소·innerHTML·console 미사용")
	void pageStructure() throws Exception {
		String html = page();
		assertThat(Pattern.compile("<input|<textarea|<select|<form").matcher(html).find()).isFalse();
		assertThat(html).contains("<a id=\"manage\" class=\"link-button\" href=\"/demo/salary-account\" target=\"_blank\" rel=\"noopener noreferrer\" hidden>");
		assertThat(html).contains("const endpoint = '/demo/api/insurance/salary-account';");
		String script = html.substring(html.indexOf("<script>"));
		assertThat(script).doesNotContain("/api/internal/", "Authorization", "Bearer", "localStorage", "sessionStorage", "indexedDB",
			"document.cookie", "console.", "innerHTML", "outerHTML", "insertAdjacentHTML", "location", "history.", "URLSearchParams");
	}

	@Test
	@DisplayName("로그인 화면: 보험용 계좌 링크는 기본 숨김이고 근로자일 때만 표시하는 구조")
	void loginLink() throws Exception {
		String login = new ClassPathResource("demo/farm-login.html").getContentAsString(StandardCharsets.UTF_8);
		assertThat(login).contains("<a href=\"/demo/insurance-salary-account\" id=\"insurance-salary-link\" hidden>");
		assertThat(login).contains("document.querySelector('#insurance-salary-link').hidden = account.role !== 'WORKER';");
	}

	@Test
	@DisplayName("보안 선언: 내부 API는 INSURANCE_SERVICE GET 규칙에, demo 조회는 farm-demo 분기의 hasRole(WORKER)·화면은 permitAll, CSRF 설정 변경 없음")
	void securityDeclarations() throws Exception {
		String insurance = Files.readString(Path.of("src/main/java/com/wonday/farm/insurance/security/InsuranceSecurityConfig.java"))
			.replaceAll("\\s+", " ");
		assertThat(insurance).containsPattern("requestMatchers\\(HttpMethod\\.GET, [^)]*\"/api/internal/insurance/workers/\\*/salary-account\"[^)]*\\)"
			+ "\\.hasAuthority\\(\"INSURANCE_SERVICE\"\\)");
		assertThat(insurance).contains("securityMatcher(\"/api/internal/insurance/**\")", "addFilterBefore(new ServiceTokenFilter(token)",
			"SessionCreationPolicy.STATELESS", "new NullSecurityContextRepository()", ".anyRequest().denyAll()");

		String farm = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java")).replaceAll("\\s+", " ");
		int demo = farm.indexOf("if (environment.matchesProfiles(\"farm-demo\")) {");
		String block = farm.substring(demo, farm.indexOf("}", demo));
		assertThat(block).containsPattern("HttpMethod\\.GET, [^)]*\"/demo/insurance-salary-account\"[^)]*\\)\\.permitAll\\(\\)");
		assertThat(block).contains("auth.requestMatchers(HttpMethod.GET, \"/demo/api/insurance/salary-account\").hasRole(\"WORKER\");");
		assertThat(farm.indexOf("/demo/api/insurance/salary-account")).isEqualTo(farm.lastIndexOf("/demo/api/insurance/salary-account"));
		assertThat(farm).doesNotContain("ignoringRequestMatchers", ".csrf(", "/api/internal/insurance/workers");
	}
}

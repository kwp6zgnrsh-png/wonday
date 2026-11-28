package com.wonday.farm.demo.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사. 화면 동작은 보고서 폴더의 node 가짜 DOM 테스트가 스크립트 원문을 실행해 확인한다.
// SecurityConfig 문자열은 선언일 뿐 실제 필터 체인 동작(익명 401·CSRF 403·hasRole·세션 격리)을 보장하지 않는다.
@DisplayName("farm-demo CHANGE-01·02 화면·메뉴·보안 선언 (정적)")
class WorkCancellationDemoPageTest {
	private static String resource(String name) throws Exception {
		return new ClassPathResource("demo/" + name).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("취소 화면 스크립트: 위험 API·브라우저 저장소·로그·Authorization 미사용, no-referrer, 인라인 스크립트 1개")
	void page() throws Exception {
		String html = resource("farm-work-cancellation.html");
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String js = matcher.group(1);
		assertThat(matcher.find()).isFalse();
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization", "setTimeout", "setInterval");
		assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\">").doesNotContain("<script src");
	}

	@Test
	@DisplayName("로그인 메뉴: 취소 링크 기본 숨김, 고용주일 때만 표시")
	void loginLink() throws Exception {
		String html = resource("farm-login.html");
		assertThat(html).contains(
			"<a href=\"/demo/work-cancellation\" id=\"cancellation-link\" hidden>",
			"document.querySelector('#cancellation-link').hidden = account.role !== 'EMPLOYER';");

		// style 안의 display: block 규칙에서 선택자를 분리해 메뉴 순서와 무관하게 확인한다.
		Matcher style = Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(html);
		assertThat(style.find()).as("로그인 화면의 CSS 영역").isTrue();
		Pattern blockDisplay = Pattern.compile("(?:^|;)\\s*display\\s*:\\s*block\\s*(?:;|$)");
		Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(style.group(1));
		var selectors = rules.results()
			.filter(rule -> blockDisplay.matcher(rule.group(2)).find())
			.flatMap(rule -> Arrays.stream(rule.group(1).split(",")))
			.map(String::strip)
			.toList();
		assertThat(selectors).as("메뉴 표시 CSS 규칙의 선택자 집합").contains("#cancellation-link");
	}
	@Test
	@DisplayName("SecurityConfig 선언: 화면 permitAll·준비 EMPLOYER는 farm-demo 블록, 정식 취소 POST EMPLOYER, 403 JSON 분기")
	void security() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java"), StandardCharsets.UTF_8);
		int demoStart = config.indexOf("environment.matchesProfiles(\"farm-demo\")");
		int commonStart = config.indexOf("auth.requestMatchers(HttpMethod.GET, \"/\", \"/api/auth/csrf\")");
		assertThat(demoStart).isPositive();
		assertThat(commonStart).isGreaterThan(demoStart);
		for (String line : new String[] {"auth.requestMatchers(HttpMethod.GET, \"/demo/work-cancellation\").permitAll();",
				"auth.requestMatchers(HttpMethod.POST, \"/demo/api/work-cancellation/fixtures\").hasRole(\"EMPLOYER\");"}) {
			assertThat(config.indexOf(line)).as(line).isGreaterThan(demoStart).isLessThan(commonStart);
			assertThat(config.indexOf(line, config.indexOf(line) + 1)).as("중복 선언 " + line).isEqualTo(-1);
		}
		String api = ".requestMatchers(HttpMethod.POST, \"/api/work-assignments/*/cancellation\").hasRole(\"EMPLOYER\")";
		assertThat(config.indexOf(api)).isGreaterThan(commonStart);
		assertThat(config.indexOf(api)).isLessThan(config.indexOf(".anyRequest().denyAll()"));
		assertThat(config).doesNotContain("\"/demo/api/work-cancellation/fixtures\").permitAll()",
			"\"/api/work-assignments/*/cancellation\").permitAll()", "\"/demo/api/work-cancellation/fixtures\").hasAnyRole");
		// CSRF 예외 목록에 새 경로가 없는지(문자열 선언 기준)
		assertThat(config).doesNotContain("ignoringRequestMatchers(\"/api/work-assignments", "ignoringRequestMatchers(\"/demo/api/work-cancellation");
		assertThat(config).contains("{\\\"code\\\":\\\"EMPLOYER_ROLE_REQUIRED\\\",\\\"message\\\":\\\"고용주 계정만 본인 공고의 확정 근무를 취소할 수 있습니다.\\\"}");
		Pattern cancellation = Pattern.compile("/api/work-assignments/[^/]+/cancellation");
		assertThat(cancellation.matcher("/api/work-assignments/31/cancellation").matches()).isTrue();
		assertThat(cancellation.matcher("/api/work-assignments/31/completion").matches()).isFalse();
		assertThat(cancellation.matcher("/api/work-assignments/31/cancellation/x").matches()).isFalse();
	}
}

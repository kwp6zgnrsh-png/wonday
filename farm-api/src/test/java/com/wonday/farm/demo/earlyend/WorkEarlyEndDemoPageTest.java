package com.wonday.farm.demo.earlyend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사. 화면 동작은 보고서 폴더의 node 가짜 DOM 테스트가 확인한다.
// SecurityConfig 문자열은 선언일 뿐 실제 필터 체인 동작(익명 401·CSRF 403·hasRole·세션 격리)을 보장하지 않는다.
@DisplayName("farm-demo CHANGE-03 화면·메뉴·보안 선언 (정적)")
class WorkEarlyEndDemoPageTest {
	private static String resource(String name) throws Exception {
		return new ClassPathResource("demo/" + name).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("화면 스크립트: 위험 API·웹 저장소·로그·Authorization 미사용, no-referrer, 인라인 스크립트 1개")
	void page() throws Exception {
		String html = resource("farm-work-early-end.html");
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String js = matcher.group(1);
		assertThat(matcher.find()).isFalse();
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization", "setTimeout", "setInterval");
		assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\">").doesNotContain("<script src");
	}

	@Test
	@DisplayName("로그인 메뉴: 조기 종료 링크 기본 숨김, 고용주일 때만 표시")
	void loginLink() throws Exception {
		assertThat(resource("farm-login.html")).contains("<a href=\"/demo/work-early-end\" id=\"early-end-link\" hidden>",
			"document.querySelector('#early-end-link').hidden = account.role !== 'EMPLOYER';", "#selection-link, #early-end-link {");
	}

	@Test
	@DisplayName("SecurityConfig 선언: 화면 permitAll·준비 EMPLOYER는 farm-demo 블록, 정식 POST EMPLOYER, CSRF 예외 없음, 403 JSON 분기")
	void security() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java"), StandardCharsets.UTF_8);
		int demoStart = config.indexOf("environment.matchesProfiles(\"farm-demo\")");
		int commonStart = config.indexOf("auth.requestMatchers(HttpMethod.GET, \"/\", \"/api/auth/csrf\")");
		assertThat(demoStart).isPositive();
		assertThat(commonStart).isGreaterThan(demoStart);
		for (String line : new String[] {"auth.requestMatchers(HttpMethod.GET, \"/demo/work-early-end\").permitAll();",
				"auth.requestMatchers(HttpMethod.POST, \"/demo/api/work-early-end/fixtures\").hasRole(\"EMPLOYER\");"}) {
			int at = config.indexOf(line);
			assertThat(at).as(line).isGreaterThan(demoStart).isLessThan(commonStart);
			assertThat(config.indexOf(line, at + 1)).as("중복 " + line).isEqualTo(-1);
		}
		String api = ".requestMatchers(HttpMethod.POST, \"/api/work-assignments/*/early-end\").hasRole(\"EMPLOYER\")";
		assertThat(config.indexOf(api)).isGreaterThan(commonStart).isLessThan(config.indexOf(".anyRequest().denyAll()"));
		assertThat(config).doesNotContain("\"/api/work-assignments/*/early-end\").permitAll()", "\"/demo/api/work-early-end/fixtures\").permitAll()",
			"\"/demo/api/work-early-end/fixtures\").hasAnyRole", "ignoringRequestMatchers(\"/api/work-assignments", "ignoringRequestMatchers(\"/demo/api/work-early-end");
		assertThat(config).contains("{\\\"code\\\":\\\"EMPLOYER_ROLE_REQUIRED\\\",\\\"message\\\":\\\"고용주 계정만 본인 공고의 조기 종료를 기록할 수 있습니다.\\\"}");
		Pattern earlyEnd = Pattern.compile("/api/work-assignments/[^/]+/early-end");
		assertThat(earlyEnd.matcher("/api/work-assignments/31/early-end").matches()).isTrue();
		assertThat(earlyEnd.matcher("/api/work-assignments/31/completion").matches()).isFalse();
		assertThat(earlyEnd.matcher("/api/work-assignments/31/early-end/x").matches()).isFalse();
	}
}

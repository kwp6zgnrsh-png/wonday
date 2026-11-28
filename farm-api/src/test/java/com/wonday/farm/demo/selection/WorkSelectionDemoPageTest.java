package com.wonday.farm.demo.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사. 화면 동작은 보고서 폴더의 node 가짜 DOM 테스트가 스크립트 원문을 실행해 확인한다.
// SecurityConfig 문자열은 선언일 뿐 실제 필터 체인 동작(401·CSRF·hasRole)을 보장하지 않는다.
@DisplayName("farm-demo WORK-02 화면·메뉴·보안 선언 (정적)")
class WorkSelectionDemoPageTest {
	private static String resource(String name) throws Exception {
		return new ClassPathResource("demo/" + name).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("선정 화면 스크립트: 위험 API·브라우저 저장소·로그·Authorization 미사용, no-referrer")
	void page() throws Exception {
		String html = resource("farm-work-selection.html");
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String js = matcher.group(1);
		assertThat(matcher.find()).isFalse();
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization");
		assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\">");
	}

	@Test
	@DisplayName("메뉴: 로그인 화면 선정 링크는 기본 숨김, 고용주·근로자일 때 표시; 내 지원·지원자 화면에서 선정 화면 링크")
	void links() throws Exception {
		assertThat(resource("farm-login.html")).contains("<a href=\"/demo/work-selection\" id=\"selection-link\" hidden>",
			"document.querySelector('#selection-link').hidden = !['EMPLOYER', 'WORKER'].includes(account.role);");
		assertThat(resource("farm-my-applications.html")).contains("<a href=\"/demo/work-selection\">");
		assertThat(resource("farm-work-applicants.html")).contains("<a href=\"/demo/work-selection\">");
	}

	@Test
	@DisplayName("APP-04·WORK-01 화면: 확인 상태 표시 함수가 textContent 경로로 근거·마지막 재확인 시각을 표시 (문자열 선언)")
	void confirmationLabels() throws Exception {
		for (var name : new String[] {"farm-my-applications.html", "farm-work-applicants.html"}) {
			String html = resource(name);
			assertThat(html).as(name).contains("info.source === 'INITIAL_APPLICATION' ? '최초 지원으로 최신 조건 확인' : '최신 조건 재확인 완료'",
				"'최신 조건 재확인 필요'", "'\\n마지막 재확인: ' + info.acknowledgedAt");
		}
	}

	@Test
	@DisplayName("SecurityConfig 선언: 화면 permitAll·준비/조회 hasAnyRole은 farm-demo 블록, 선정 EMPLOYER·재확인 WORKER, 403 JSON 경로")
	void security() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java"), StandardCharsets.UTF_8);
		int demoStart = config.indexOf("environment.matchesProfiles(\"farm-demo\")");
		int commonStart = config.indexOf("auth.requestMatchers(HttpMethod.GET, \"/\", \"/api/auth/csrf\")");
		assertThat(demoStart).isPositive();
		for (String line : new String[] {"auth.requestMatchers(HttpMethod.GET, \"/demo/work-selection\").permitAll();",
				"auth.requestMatchers(HttpMethod.GET, \"/demo/api/work-selection/fixtures\").hasAnyRole(\"EMPLOYER\", \"WORKER\");",
				"auth.requestMatchers(HttpMethod.POST, \"/demo/api/work-selection/fixtures\").hasAnyRole(\"EMPLOYER\", \"WORKER\");"}) {
			assertThat(config.indexOf(line)).as(line).isGreaterThan(demoStart).isLessThan(commonStart);
		}
		assertThat(config).contains(
			".requestMatchers(HttpMethod.POST, \"/api/job-postings/*/schedules/*/applications/*/selection\").hasRole(\"EMPLOYER\")",
			".requestMatchers(HttpMethod.POST, \"/api/me/applications/*/condition-confirmation\").hasRole(\"WORKER\")",
			"{\\\"code\\\":\\\"WORKER_ROLE_REQUIRED\\\"", "{\\\"code\\\":\\\"EMPLOYER_ROLE_REQUIRED\\\"", "{\\\"code\\\":\\\"FARM_ROLE_REQUIRED\\\"")
			.doesNotContain("\"/demo/api/work-selection/fixtures\").permitAll()",
				"\"/api/job-postings/*/schedules/*/applications/*/selection\").permitAll()");
		Pattern selection = Pattern.compile("/api/job-postings/[^/]+/schedules/[^/]+/applications/[^/]+/selection");
		assertThat(selection.matcher("/api/job-postings/1/schedules/2/applications/3/selection").matches()).isTrue();
		assertThat(selection.matcher("/api/job-postings/1/schedules/2/applications/3/contact").matches()).isFalse();
	}
}

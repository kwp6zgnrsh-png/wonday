package com.wonday.farm.demo.applicant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사. 스크립트 동작은 보고서 폴더의 node 가짜 DOM 테스트가 원문을 실행해 확인한다.
// SecurityConfig 문자열은 선언일 뿐 실제 필터 체인 동작을 보장하지 않는다.
@DisplayName("farm-demo WORK-01 화면·로그인 링크·보안 선언 (정적)")
class WorkApplicantsDemoPageTest {
	private static String resource(String name) throws Exception {
		return new ClassPathResource("demo/" + name).getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("화면 스크립트: 위험 API·브라우저 저장소·로그·Authorization 미사용, POST는 준비 한 곳")
	void page() throws Exception {
		String html = resource("farm-work-applicants.html");
		Matcher matcher = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL).matcher(html);
		assertThat(matcher.find()).isTrue();
		String js = matcher.group(1);
		assertThat(matcher.find()).isFalse();
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization");
		assertThat(js.split("method:'POST'", -1)).hasSize(2);
		assertThat(js).contains("call('/demo/api/work-applicants/fixtures',context,account,{method:'POST',headers:{[token.body.headerName]:token.body.token}})");
		assertThat(html).contains("<meta name=\"referrer\" content=\"no-referrer\">");
	}

	@Test
	@DisplayName("로그인 화면: 지원자 링크는 기본 숨김, 고용주일 때만 표시")
	void loginLink() throws Exception {
		String login = resource("farm-login.html");
		assertThat(login).contains("<a href=\"/demo/work-applicants\" id=\"applicants-link\" hidden>",
			"document.querySelector('#applicants-link').hidden = account.role !== 'EMPLOYER';");
	}

	@Test
	@DisplayName("SecurityConfig 선언: 화면 GET permitAll·준비 POST hasRole(EMPLOYER)은 farm-demo 블록 안, 정식 GET 3종 hasRole(EMPLOYER), 403 JSON 경로")
	void security() throws Exception {
		String config = Files.readString(Path.of("src/main/java/com/wonday/farm/auth/SecurityConfig.java"), StandardCharsets.UTF_8);
		int demoStart = config.indexOf("environment.matchesProfiles(\"farm-demo\")");
		int commonStart = config.indexOf("auth.requestMatchers(HttpMethod.GET, \"/\", \"/api/auth/csrf\")");
		for (String line : new String[] {"auth.requestMatchers(HttpMethod.GET, \"/demo/work-applicants\").permitAll();",
				"auth.requestMatchers(HttpMethod.POST, \"/demo/api/work-applicants/fixtures\").hasRole(\"EMPLOYER\");"}) {
			assertThat(config.indexOf(line)).as(line).isGreaterThan(demoStart).isLessThan(commonStart);
		}
		assertThat(config).contains(".requestMatchers(HttpMethod.GET, \"/api/job-postings/*/schedules/*/applications\",\n"
			+ "\t\t\t\t\t\t\"/api/job-postings/*/schedules/*/applications/*\",\n"
			+ "\t\t\t\t\t\t\"/api/job-postings/*/schedules/*/applications/*/contact\").hasRole(\"EMPLOYER\")");
		// 근로자용 "/api/job-postings/*" 규칙보다 먼저 선언돼야 한다(경로 패턴 겹침은 없지만 순서 확인)
		assertThat(config.indexOf("\"/api/job-postings/*/schedules/*/applications\""))
			.isLessThan(config.indexOf(".requestMatchers(HttpMethod.GET, \"/api/job-postings\", \"/api/job-postings/*\").hasRole(\"WORKER\")"));
		Pattern path = Pattern.compile("/api/job-postings/[^/]+/schedules/[^/]+/applications(/[^/]+(/contact)?)?");
		assertThat(path.matcher("/api/job-postings/1/schedules/2/applications").matches()).isTrue();
		assertThat(path.matcher("/api/job-postings/1/schedules/2/applications/3/contact").matches()).isTrue();
		assertThat(path.matcher("/api/job-postings/1/schedules/2/applications/3/other").matches()).isFalse();
		assertThat(config).contains("{\\\"code\\\":\\\"EMPLOYER_ROLE_REQUIRED\\\"")
			.doesNotContain("\"/demo/api/work-applicants/fixtures\").permitAll()");
	}
}

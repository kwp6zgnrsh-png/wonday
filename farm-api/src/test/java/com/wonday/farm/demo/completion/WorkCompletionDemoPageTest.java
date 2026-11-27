package com.wonday.farm.demo.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사다. 스크립트 동작(요청 순서·CSRF 헤더·재조회 비교·오류 표시·중복 클릭·계정 변경)은
// 보고서 폴더의 node 가짜 DOM 테스트에서 원문 스크립트를 실행해 별도로 확인한다. 실제 브라우저는 실행하지 않았다.
@DisplayName("farm-demo 근무 완료 화면·링크·좌표 화면 완료 표시 (정적)")
class WorkCompletionDemoPageTest {
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
	@DisplayName("완료 화면: 입력 폼 없음, 일반 화면 문구는 \"완료 일시\", 위험한 HTML 삽입·저장소·로그·eval 미사용")
	void completionPage() throws Exception {
		String html = resource("farm-work-completion.html");
		assertThat(Pattern.compile("<input|<textarea|<form").matcher(html).find()).isFalse();
		assertThat(html).contains("<dt>완료 일시</dt><dd id=\"completed-at\">", "<meta name=\"referrer\" content=\"no-referrer\">");
		String js = script(html);
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization", "/api/internal/");
		assertThat(js).contains("const base = '/demo/api/employer/work-assignments';",
			"'/api/work-assignments/' + target.assignmentId + '/completion'", "request('/api/auth/csrf', context)");
		assertThat(js).doesNotContain("completedBy");
	}

	@Test
	@DisplayName("로그인 화면: 완료 링크는 기본 숨김이고 고용주일 때만 표시하는 구조")
	void loginLink() throws Exception {
		String login = resource("farm-login.html");
		assertThat(login).contains("<a href=\"/demo/work-completion\" id=\"completion-link\" hidden>");
		assertThat(login).contains("document.querySelector('#completion-link').hidden = account.role !== 'EMPLOYER';");
	}

	@Test
	@DisplayName("좌표 화면: completedAt 키 존재 확인, null이면 \"미완료\", 아니면 값 표시 (필드명은 completedAt 유지)")
	void jobLocationCompletion() throws Exception {
		String js = script(resource("farm-job-location.html"));
		assertThat(js).contains("if (!Object.hasOwn(body, 'completedAt'))",
			"완료 일시: ${body.completedAt === null ? '미완료' : body.completedAt}");
		assertThat(js).doesNotContain("completedBy", "innerHTML");
	}
}

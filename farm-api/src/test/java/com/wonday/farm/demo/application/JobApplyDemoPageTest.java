package com.wonday.farm.demo.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

// 정적 문자열 검사다. 스크립트 동작은 보고서 폴더의 node 가짜 DOM 테스트에서 원문 스크립트를 실행해 별도로 확인한다.
@DisplayName("farm-demo 지원 화면·링크 (정적)")
class JobApplyDemoPageTest {
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
	@DisplayName("지원 화면: 위험한 HTML 삽입·저장소·로그·eval·Authorization 미사용, 키는 crypto.randomUUID, 버전은 조회한 일정 값")
	void applyPage() throws Exception {
		String js = script(resource("farm-job-apply.html"));
		assertThat(js).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "console.", "Authorization", "/api/internal/",
			"workerId:", "employerId");
		assertThat(js).contains("key:crypto.randomUUID()", "({workScheduleId:row.workScheduleId, termsVersion:row.termsVersion})",
			"'Idempotency-Key':bundle.key", "call('/api/auth/csrf', context, {}, false)");
		assertThat(resource("farm-job-apply.html")).contains("<meta name=\"referrer\" content=\"no-referrer\">");
	}

	@Test
	@DisplayName("로그인 화면: 지원 링크는 기본 숨김이고 근로자일 때만 표시")
	void loginLink() throws Exception {
		String login = resource("farm-login.html");
		assertThat(login).contains("<a href=\"/demo/job-apply\" id=\"apply-link\" hidden>",
			"document.querySelector('#apply-link').hidden = account.role !== 'WORKER';");
	}

	@Test
	@DisplayName("공고 목록·상세 화면: 지원 화면 링크와 상세의 jobPostingId 인코딩 링크")
	void workerJobsLink() throws Exception {
		String html = resource("farm-worker-jobs.html");
		assertThat(html).contains("<a class=\"button\" href=\"/demo/job-apply\">",
			"<a id=\"detail-apply-link\" class=\"button\" href=\"/demo/job-apply\">",
			"$('detail-apply-link').href = `/demo/job-apply?jobPostingId=${encodeURIComponent(posting.jobPostingId)}`;");
	}
}

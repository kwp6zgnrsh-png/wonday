package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;

// AuthDemoPage가 실제 classpath 리소스를 돌려주는지와 로그인 화면 링크만 확인한다.
// 아래 문자열 검사는 정적 회귀 방지용이며, 브라우저 동작(CSRF 자동 처리·화면 갱신)을 검증한 것이 아니다.
@DisplayName("farm-demo 좌표 확인 페이지 리소스")
class JobLocationDemoPageTest {
	private final AuthDemoPage pages = new AuthDemoPage();

	@Test
	@DisplayName("/demo/job-location은 실제 farm-job-location.html 리소스를 반환하고 읽을 수 있다")
	void servesLocationPage() throws Exception {
		Resource page = pages.jobLocationPage();

		assertThat(page).isInstanceOf(ClassPathResource.class);
		assertThat(((ClassPathResource) page).getPath()).isEqualTo("demo/farm-job-location.html");
		assertThat(page.exists()).isTrue();
		assertThat(page.getContentAsString(StandardCharsets.UTF_8)).contains("<h1>작업지 좌표 등록 · 조회 확인</h1>");
	}

	@Test
	@DisplayName("매핑 경로·HTML 타입·farm-demo 프로필 선언 (실제 프로필별 등록은 미검증)")
	void mappingDeclared() throws Exception {
		GetMapping mapping = AuthDemoPage.class.getDeclaredMethod("jobLocationPage").getAnnotation(GetMapping.class);

		assertThat(mapping.value()).containsExactly("/demo/job-location");
		assertThat(mapping.produces()).containsExactly("text/html;charset=UTF-8");
		assertThat(AuthDemoPage.class.getAnnotation(Profile.class).value()).containsExactly("farm-demo");
	}

	@Test
	@DisplayName("로그인 화면에 좌표 확인 화면으로 이동하는 링크가 있다")
	void loginLinksToLocationPage() throws Exception {
		String login = pages.loginPage().getContentAsString(StandardCharsets.UTF_8);

		assertThat(login).contains("<a href=\"/demo/job-location\" id=\"location-link\">작업지 좌표 등록 · 조회 확인</a>");
	}

	@Test
	@DisplayName("기존 demo 페이지 리소스도 누락 없이 존재한다")
	void allPagesExist() {
		for (Resource page : List.of(pages.loginPage(), pages.jobsPage(), pages.workerJobsPage(), pages.jobLocationPage())) {
			assertThat(page.exists()).as(page.getDescription()).isTrue();
		}
	}

	@Test
	@DisplayName("정적 회귀 방지: 좌표 페이지 스크립트는 innerHTML·브라우저 저장소·Authorization 헤더·보험 내부 경로를 쓰지 않는다")
	void staticGuards() throws Exception {
		String html = pages.jobLocationPage().getContentAsString(StandardCharsets.UTF_8);

		assertThat(html).doesNotContain("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write",
			"localStorage", "sessionStorage", "indexedDB", "document.cookie", "Authorization",
			"/api/internal/insurance", "parseFloat", "Number($('latitude')", "Number($('longitude')");
	}
}

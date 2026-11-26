package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;

// /demo/signup 리소스와 로그인 화면 링크만 확인한다. 아래 문자열 검사는 정적 회귀 방지이며
// 마스킹·자동 하이픈·커서·CSRF 흐름 같은 브라우저 동작을 검증한 것이 아니다.
@DisplayName("farm-demo 회원가입 페이지 리소스")
class SignupDemoPageTest {
	private final AuthDemoPage pages = new AuthDemoPage();

	@Test
	@DisplayName("/demo/signup은 실제 farm-signup.html을 반환하고 매핑·HTML 타입이 선언돼 있다")
	void servesSignupPage() throws Exception {
		Resource page = pages.signupPage();

		assertThat(((ClassPathResource) page).getPath()).isEqualTo("demo/farm-signup.html");
		assertThat(page.exists()).isTrue();
		GetMapping mapping = AuthDemoPage.class.getDeclaredMethod("signupPage").getAnnotation(GetMapping.class);
		assertThat(mapping.value()).containsExactly("/demo/signup");
		assertThat(mapping.produces()).containsExactly("text/html;charset=UTF-8");
	}

	@Test
	@DisplayName("로그인 화면에 회원가입 링크와 국적 표시 칸이 있다")
	void loginLinks() throws Exception {
		String login = pages.loginPage().getContentAsString(StandardCharsets.UTF_8);

		assertThat(login).contains("<a href=\"/demo/signup\" id=\"signup-link\">", "id=\"member-nationality\"");
	}

	@Test
	@DisplayName("정적 회귀 방지: 마지막 6자리는 password 입력, 브라우저 저장소·innerHTML·쿠키 직접 접근이 없다")
	void staticGuards() throws Exception {
		String html = pages.signupPage().getContentAsString(StandardCharsets.UTF_8);

		assertThat(html).contains("id=\"identity-secret\" name=\"identitySecret\" type=\"password\"",
			"id=\"identity-front\" name=\"identityFront\" type=\"text\" inputmode=\"numeric\" maxlength=\"6\"",
			"id=\"identity-first\" name=\"identityFirst\" type=\"text\" inputmode=\"numeric\" maxlength=\"1\"",
			"id=\"account-number\" name=\"accountNumber\" type=\"text\"");
		assertThat(html).doesNotContain("localStorage", "sessionStorage", "indexedDB", "document.cookie", "innerHTML",
			"outerHTML", "insertAdjacentHTML", "document.write");
	}
}

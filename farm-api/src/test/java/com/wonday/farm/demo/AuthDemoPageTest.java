package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

class AuthDemoPageTest {

	@Test
	void servesBundledLoginPage() throws Exception {
		Resource page = new AuthDemoPage().loginPage();

		assertThat(page.exists()).isTrue();
		assertThat(page.getContentAsString(StandardCharsets.UTF_8)).contains("로그인 데모");
	}
}

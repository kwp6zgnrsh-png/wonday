package com.wonday.farm.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// farm-demo로 실행할 때만 브라우저에서 로그인 흐름을 확인할 수 있는 페이지를 제공한다.
@Profile("farm-demo")
@RestController
class AuthDemoPage {
	@GetMapping(value = "/demo/login", produces = "text/html;charset=UTF-8")
	Resource loginPage() {
		return new ClassPathResource("demo/farm-login.html");
	}
}

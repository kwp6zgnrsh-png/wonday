package com.wonday.farm.demo.application;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// HTML만 제공한다. 데이터는 기존 APP-04 API가 세션·현재 역할·소유자를 확인해 조회한다.
@Profile("farm-demo")
@RestController
public class DemoMyApplicationPage {
	@GetMapping(value = "/demo/my-applications", produces = "text/html;charset=UTF-8")
	public ResponseEntity<Resource> page() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new ClassPathResource("demo/farm-my-applications.html"));
	}
}

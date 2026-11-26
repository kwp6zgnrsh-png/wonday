package com.wonday.farm.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// farm-demo로 실행할 때만 로그인과 공고·일정 확인용 페이지를 제공한다.
@Profile("farm-demo")
@RestController
class AuthDemoPage {
	@GetMapping(value = "/demo/login", produces = "text/html;charset=UTF-8")
	Resource loginPage() {
		return new ClassPathResource("demo/farm-login.html");
	}

	@GetMapping(value = "/demo/jobs", produces = "text/html;charset=UTF-8")
	Resource jobsPage() {
		return new ClassPathResource("demo/farm-jobs.html");
	}

	@GetMapping(value = "/demo/worker-jobs", produces = "text/html;charset=UTF-8")
	Resource workerJobsPage() {
		return new ClassPathResource("demo/farm-worker-jobs.html");
	}

	@GetMapping(value = "/demo/job-location", produces = "text/html;charset=UTF-8")
	Resource jobLocationPage() {
		return new ClassPathResource("demo/farm-job-location.html");
	}
}

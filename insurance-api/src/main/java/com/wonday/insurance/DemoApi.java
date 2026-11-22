package com.wonday.insurance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoApi {

	@GetMapping("/")
	public String home() {
		return "보험 API 실행 중";
	}
}
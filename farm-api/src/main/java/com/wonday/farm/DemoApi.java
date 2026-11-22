package com.wonday.farm;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoApi {

	@GetMapping("/")
	public String home() {
		return "플랫폼 API 실행 중";
	}
}
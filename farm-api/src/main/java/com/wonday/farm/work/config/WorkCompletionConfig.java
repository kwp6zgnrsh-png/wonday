package com.wonday.farm.work.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkCompletionConfig {
	// 서버 시각을 주입받아 경계 시각을 검증할 수 있게 한다. DB에는 UTC로 저장한다.
	@Bean
	Clock workCompletionClock() { return Clock.systemUTC(); }
}
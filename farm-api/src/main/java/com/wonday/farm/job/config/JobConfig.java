package com.wonday.farm.job.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JobConfig {
	@Bean
	Clock jobClock() { return Clock.systemUTC(); }
}

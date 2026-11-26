package com.wonday.farm.insurance.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InsuranceIntegrationConfig {
	@Bean
	Clock insuranceChangeClock() { return Clock.systemUTC(); }
}

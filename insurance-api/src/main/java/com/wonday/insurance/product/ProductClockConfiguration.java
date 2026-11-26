package com.wonday.insurance.product;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ProductClockConfiguration {

	@Bean
	Clock productClock() {
		// 실행 PC의 기본 시간대와 무관하게 저장·비교 시각을 제공한다. 업무 날짜 계산 시 GMT+9로 변환한다.
		return Clock.systemUTC();
	}
}

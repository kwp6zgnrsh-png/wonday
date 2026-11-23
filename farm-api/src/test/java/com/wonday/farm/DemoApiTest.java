package com.wonday.farm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoApiTest {

	@Test
	void homeReportsApiIsRunning() {
		assertThat(new DemoApi().home()).isEqualTo("플랫폼 API 실행 중");
	}
}

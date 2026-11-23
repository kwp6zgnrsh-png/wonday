package com.wonday.farm.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.testsupport.FarmAuthClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

// 데모 시작 시드가 개발 DB에 기록되지 않도록 이 테스트는 전용 인메모리 DB를 사용한다.
@Tag("integration")
@ActiveProfiles("farm-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.datasource.url=jdbc:h2:mem:farm-demo-${random.uuid};MODE=MySQL",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"FARM_DEMO_EMPLOYER_PASSWORD=employer-profile-test",
	"FARM_DEMO_WORKER_PASSWORD=worker-profile-test"
})
class DemoProfileTests {
	@LocalServerPort
	private int port;

	@Autowired
	private MemberRepository members;

	@Test
	void demoProfileServesPageAndRegistersBothAccounts() throws Exception {
		try (var client = new FarmAuthClient(port)) {
			var page = client.get("/demo/login");
			assertThat(page.statusCode()).isEqualTo(200);
			assertThat(page.body()).contains("로그인 데모");
		}
		assertThat(members.count()).isEqualTo(2);
		assertDemoLogin("demo-employer", "employer-profile-test", "EMPLOYER");
		assertDemoLogin("demo-worker", "worker-profile-test", "WORKER");
	}

	private void assertDemoLogin(String loginId, String password, String role) throws Exception {
		try (var client = new FarmAuthClient(port)) {
			assertThat(client.login(loginId, password, client.csrf()).statusCode()).isEqualTo(204);
			var me = client.get("/api/auth/me");
			assertThat(me.statusCode()).isEqualTo(200);
			assertThat(JsonPath.<String>read(me.body(), "$.role")).isEqualTo(role);
		}
	}
}

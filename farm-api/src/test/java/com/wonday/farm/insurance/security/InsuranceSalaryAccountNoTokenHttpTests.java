package com.wonday.farm.insurance.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.wonday.farm.demo.insurance.DemoInsuranceSalaryAccountApi;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 농업 세션·CSRF가 필요한 기본 프로필과 내부 토큰 미설정 상태를 별도 실제 서버에서 확인한다.
@Tag("integration")
@ActiveProfiles("insurance-salary-security-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "farm.insurance.service-token=")
@Import(MySqlTestDatabase.class)
class InsuranceSalaryAccountNoTokenHttpTests extends InsuranceSalaryAccountHttpSupport {
	@Autowired
	private ApplicationContext context;

	@Test
	void unconfiguredServiceTokenRejectsMissingAndProvidedBearer() throws Exception {
		var worker = member(MemberRole.WORKER, "020");
		tokenRejected(get(internal(worker.getId()), null, null));
		tokenRejected(get(internal(worker.getId()), "Bearer " + SERVICE_TOKEN, null));
	}

	@Test
	void inactiveFarmDemoHasNoControllerAndExposesNeitherPageNorApi() throws Exception {
		assertThat(context.getBeansOfType(DemoInsuranceSalaryAccountApi.class)).isEmpty();
		var worker = member(MemberRole.WORKER, null);
		var browser = login(worker);
		// 데모 비활성 시 경로를 허용하지 않으므로 로그인한 요청도 denyAll의 403으로 차단된다.
		assertThat(get(DEMO_PAGE, null, browser.sessionId()).statusCode()).isEqualTo(403);
		var response = get(DEMO_API, null, browser.sessionId());
		assertThat(response.statusCode()).isEqualTo(403);
		noStore(response);
		assertThat(response.body()).doesNotContain("bankCode", "registered");
	}
}

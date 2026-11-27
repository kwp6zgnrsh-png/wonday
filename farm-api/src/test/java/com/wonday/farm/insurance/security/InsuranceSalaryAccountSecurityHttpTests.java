package com.wonday.farm.insurance.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.MySqlTestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// 실제 애플리케이션·두 보안 체인·서블릿·JPA를 띄운 임의 포트에 HTTP 요청한다. mock principal은 사용하지 않는다.
@Tag("integration")
@ActiveProfiles("farm-demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "farm.insurance.service-token=" + InsuranceSalaryAccountHttpSupport.SERVICE_TOKEN)
@Import(MySqlTestDatabase.class)
class InsuranceSalaryAccountSecurityHttpTests extends InsuranceSalaryAccountHttpSupport {
	@Test
	void validBearerReturnsAccountWithoutCreatingFarmSession() throws Exception {
		var worker = member(MemberRole.WORKER, "020");
		var response = get(internal(worker.getId()), "Bearer " + SERVICE_TOKEN, null);
		account(response, worker.getId(), "020");
		assertThat(response.headers().allValues("Set-Cookie"))
			.noneMatch(cookie -> cookie.startsWith("FARMSESSION="));
	}

	@Test
	void validBearerReturnsUnregisteredWorkerFromActualLeftJoin() throws Exception {
		var worker = member(MemberRole.WORKER, null);
		account(get(internal(worker.getId()), "Bearer " + SERVICE_TOKEN, null), worker.getId(), null);
	}

	@Test
	void validBearerDoesNotReturnEmployerAccount() throws Exception {
		var employer = member(MemberRole.EMPLOYER, "088");
		var response = get(internal(employer.getId()), "Bearer " + SERVICE_TOKEN, null);
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(json(response).get("code").asString()).isEqualTo("WORKER_NOT_FOUND");
		noStore(response);
		assertThat(response.body()).doesNotContain("088", "bankCode");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Bearer wrong-service-token"})
	void missingOrWrongBearerIsRejectedByActualChain(String authorization) throws Exception {
		var worker = member(MemberRole.WORKER, "020");
		tokenRejected(get(internal(worker.getId()), authorization.isEmpty() ? null : authorization, null));
	}

	@ParameterizedTest
	@EnumSource(value = MemberRole.class, names = {"WORKER", "EMPLOYER"})
	void realFarmLoginCookieCannotAuthenticateInternalApi(MemberRole role) throws Exception {
		var member = member(role, "020");
		var browser = login(member);
		// 실제 로그인에서 발급된 FARMSESSION만 별도 HTTP 클라이언트로 재전송한다.
		tokenRejected(get(internal(member.getId()), null, browser.sessionId()));
		// 내부 체인의 거절이 농업 앱 세션 자체를 삭제하지도 않아야 한다.
		assertThat(browser.get("/api/auth/me").statusCode()).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/me", "/api/me/salary-account", DEMO_API})
	void serviceTokenCannotReplaceFarmSession(String path) throws Exception {
		var response = get(path, "Bearer " + SERVICE_TOKEN, null);
		assertThat(response.statusCode()).isEqualTo(401);
		noStore(response);
	}

	@Test
	void anonymousCannotReadDemoAccountButCanOpenDemoPage() throws Exception {
		var denied = get(DEMO_API, null, null);
		assertThat(denied.statusCode()).isEqualTo(401);
		noStore(denied);
		var page = get(DEMO_PAGE, null, null);
		assertThat(page.statusCode()).isEqualTo(200);
		noStore(page);
		assertThat(page.body()).contains("보험용 급여 계좌 조회").doesNotContain(SERVICE_TOKEN);
	}

	@Test
	void employerCookieCannotReadDemoAccount() throws Exception {
		var employer = member(MemberRole.EMPLOYER, "088");
		var browser = login(employer);
		var denied = get(DEMO_API, null, browser.sessionId());
		assertThat(denied.statusCode()).isEqualTo(403);
		noStore(denied);
		assertThat(json(denied).has("bankCode")).isFalse();
		assertThat(json(denied).has("registered")).isFalse();
	}

	@Test
	void workerCookieReadsOnlyOwnDemoAccountEvenWithOtherIdsInQuery() throws Exception {
		var worker = member(MemberRole.WORKER, "020");
		var other = member(MemberRole.WORKER, "088");
		var browser = login(worker);
		var response = get(DEMO_API + "?workerId=" + other.getId() + "&memberId=" + other.getId(),
			null, browser.sessionId());
		account(response, worker.getId(), "020");
	}
}

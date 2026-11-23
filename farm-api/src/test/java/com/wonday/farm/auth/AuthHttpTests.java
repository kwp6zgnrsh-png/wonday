package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import static com.wonday.farm.testsupport.FarmAuthClient.credentials;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.testsupport.FarmAuthClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpTests {

	@LocalServerPort
	private int port;

	@Autowired
	private MemberRepository members;

	@Autowired
	private PasswordEncoder encoder;

	@Test
	void loginWithoutCsrfReturnsForbiddenOverHttp() throws Exception {
		// 실제 서버까지 요청을 보내 오류 처리 후에도 CSRF의 403 상태가 유지되는지 확인한다.
		try (var client = new FarmAuthClient(port)) {
			assertThat(client.postForm("/api/auth/login", credentials("csrf-test", "sample"), null).statusCode())
				.isEqualTo(403);
		}
	}

	@Test
	void loginAcceptsCredentialsOnlyFromFormBody() throws Exception {
		String password = "form-body-test";
		Member account = register(password);
		try (var client = new FarmAuthClient(port)) {
			var csrf = client.csrf();
			String form = credentials(account.getLoginId(), password);
			// 같은 테스트 계정과 CSRF 토큰으로 전송 위치만 바꿔 상태 코드를 비교한다.
			assertThat(client.postForm("/api/auth/login?" + form, "", csrf).statusCode()).isEqualTo(400);
			assertThat(client.postForm("/api/auth/login?source=test", form, csrf).statusCode()).isEqualTo(400);
			assertThat(client.post("/api/auth/login", MediaType.APPLICATION_JSON_VALUE, "{}", csrf).statusCode())
				.isEqualTo(415);
			assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(client.login(account.getLoginId(), password, csrf).statusCode()).isEqualTo(204);
			assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(200);
		} finally {
			members.deleteById(account.getId());
		}
	}

	@Test
	void loginEnforcesBcryptLimitInUtf8Bytes() throws Exception {
		String password = "a".repeat(69) + "한";
		assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		Member account = register(password);
		try (var client = new FarmAuthClient(port)) {
			var csrf = client.csrf();
			assertThat(client.login(account.getLoginId(), password + "x", csrf).statusCode()).isEqualTo(401);
			assertThat(client.login(account.getLoginId(), password + "글", csrf).statusCode()).isEqualTo(401);
			assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
			assertThat(client.login(account.getLoginId(), password, csrf).statusCode()).isEqualTo(204);
		} finally {
			members.deleteById(account.getId());
		}
	}

	@Test
	void logoutInvalidatesSessionOnServer() throws Exception {
		String password = "logout-test";
		Member account = register(password);
		try (var client = new FarmAuthClient(port); var replay = new FarmAuthClient(port)) {
			assertThat(client.login(account.getLoginId(), password, client.csrf()).statusCode()).isEqualTo(204);
			String sessionId = client.sessionId();
			// 대조군: 로그아웃 전에는 다른 클라이언트가 같은 세션 쿠키를 보내도 본인 조회에 성공한다.
			assertThat(replay.getWithSession("/api/auth/me", sessionId).statusCode()).isEqualTo(200);

			assertThat(client.postForm("/api/auth/logout", "", client.csrf()).statusCode()).isEqualTo(204);

			// 로그아웃 후 같은 세션 쿠키를 다시 보내면 서버가 세션을 폐기했으므로 401이어야 한다.
			assertThat(replay.getWithSession("/api/auth/me", sessionId).statusCode()).isEqualTo(401);
		} finally {
			members.deleteById(account.getId());
		}
	}

	private Member register(String password) {
		// 실제 HTTP 요청은 별도 트랜잭션에서 실행되므로 테스트 계정을 먼저 저장한다.
		return members.save(new Member("http-test-" + UUID.randomUUID(), encoder.encode(password),
			"HTTP 테스트 계정", MemberRole.EMPLOYER));
	}

}

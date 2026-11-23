package com.wonday.farm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FarmApiApplicationTests {

	@LocalServerPort
	private int port;

	@Test
	void loginWithoutCsrfReturnsForbiddenOverHttp() throws Exception {
		// 실제 서버까지 요청을 보내 오류 처리 후에도 CSRF의 403 상태가 유지되는지 확인한다.
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString("loginId=csrf-test&password=sample"))
			.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			assertThat(response.statusCode()).isEqualTo(403);
		}
	}

}

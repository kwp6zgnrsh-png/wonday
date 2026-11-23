package com.wonday.farm.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;

// 실제 HTTP 테스트에서 브라우저처럼 CSRF 토큰과 세션 쿠키를 요청 사이에 유지한다.
public final class FarmAuthClient implements AutoCloseable {
	private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
	private final URI baseUri;

	public FarmAuthClient(int port) {
		baseUri = URI.create("http://127.0.0.1:" + port);
	}

	public HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	public CsrfHeader csrf() throws IOException, InterruptedException {
		var response = get("/api/auth/csrf");
		assertThat(response.statusCode()).isEqualTo(200);
		return new CsrfHeader(JsonPath.read(response.body(), "$.headerName"), JsonPath.read(response.body(), "$.token"));
	}

	public HttpResponse<String> login(String loginId, String password, CsrfHeader csrf)
			throws IOException, InterruptedException {
		return postForm("/api/auth/login", credentials(loginId, password), csrf);
	}

	public HttpResponse<String> postForm(String path, String body, CsrfHeader csrf)
			throws IOException, InterruptedException {
		return post(path, MediaType.APPLICATION_FORM_URLENCODED_VALUE, body, csrf);
	}

	public HttpResponse<String> post(String path, String contentType, String body, CsrfHeader csrf)
			throws IOException, InterruptedException {
		var request = request(path).header("Content-Type", contentType);
		if (csrf != null) {
			request.header(csrf.name(), csrf.value());
		}
		return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(10));
	}

	public static String credentials(String loginId, String password) {
		return "loginId=" + URLEncoder.encode(loginId, StandardCharsets.UTF_8)
			+ "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		client.close();
	}

	public record CsrfHeader(String name, String value) {}
}

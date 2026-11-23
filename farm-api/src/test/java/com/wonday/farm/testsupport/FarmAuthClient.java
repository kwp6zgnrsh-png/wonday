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
	public static final String SESSION_COOKIE = "FARMSESSION";

	private final CookieManager cookies = new CookieManager();
	private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
	private final URI baseUri;

	public FarmAuthClient(int port) {
		baseUri = URI.create("http://127.0.0.1:" + port);
	}

	public HttpResponse<String> get(String path) throws IOException, InterruptedException {
		return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	// 다른 클라이언트가 받은 세션 쿠키를 그대로 재전송해, 서버가 그 세션을 아직 인정하는지 확인한다.
	public HttpResponse<String> getWithSession(String path, String sessionId) throws IOException, InterruptedException {
		return client.send(request(path).header("Cookie", SESSION_COOKIE + "=" + sessionId).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	public String sessionId() {
		return cookies.getCookieStore().getCookies().stream()
			.filter(cookie -> SESSION_COOKIE.equals(cookie.getName()))
			.map(cookie -> cookie.getValue())
			.findFirst()
			.orElseThrow(() -> new AssertionError(SESSION_COOKIE + " cookie not set"));
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

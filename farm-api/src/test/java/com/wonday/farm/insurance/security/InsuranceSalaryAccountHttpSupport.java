package com.wonday.farm.insurance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.repository.MemberSalaryAccountRepository;
import com.wonday.farm.testsupport.FarmAuthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 .env를 읽지 않는다. ServiceConnection이 빠지면 개발 DB 대신 닫힌 포트로 실패하게 한다.
@TestPropertySource(properties = {
	"spring.config.import=",
	"spring.datasource.url=jdbc:mysql://127.0.0.1:1/do_not_use_development_db",
	"spring.datasource.username=unused-test-only",
	"spring.datasource.password=unused-test-only",
	"farm.identity.lookup-mode=LEGACY",
	"farm.identity.encryption-key=",
	"farm.identity.hmac-key=",
	"farm.salary-account.encryption-key=",
	"FARM_DEMO_EMPLOYER_PASSWORD=security-http-employer-test",
	"FARM_DEMO_WORKER_PASSWORD=security-http-worker-test"
})
abstract class InsuranceSalaryAccountHttpSupport {
	static final String SERVICE_TOKEN = "insurance-salary-http-test-token";
	static final String PASSWORD = "security-http-login-test";
	static final String DEMO_API = "/demo/api/insurance/salary-account";
	static final String DEMO_PAGE = "/demo/insurance-salary-account";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	protected int port;
	@Autowired
	private MemberRepository members;
	@Autowired
	private MemberSalaryAccountRepository accounts;
	@Autowired
	private PasswordEncoder encoder;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private MySQLContainer mysql;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NEVER).build();
	private final List<FarmAuthClient> sessions = new ArrayList<>();
	private final List<Long> fixtureMembers = new ArrayList<>();

	@BeforeEach
	void requireIsolatedDataSource() throws Exception {
		// 컨테이너의 임의 포트로 연결됐는지 확인한 뒤에만 가상 회원을 만든다.
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith(
				"jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/farm_db");
		}
	}

	protected Member member(MemberRole role, String bankCode) {
		Member member = members.saveAndFlush(new Member("salary-http-" + UUID.randomUUID(),
			encoder.encode(PASSWORD), "가상 보안 테스트 회원", role));
		fixtureMembers.add(member.getId());
		if (bankCode != null) {
			// 이 경로는 복호화하지 않는다. 암호문 원문 검증과 무관한 격리 DB 전용 fixture다.
			accounts.saveAndFlush(new MemberSalaryAccount(member.getId(), bankCode, "v1:http-test-fixture",
				"가상 예금주", LocalDateTime.now(ZoneOffset.UTC)));
		}
		return member;
	}

	protected FarmAuthClient login(Member member) throws Exception {
		var client = new FarmAuthClient(port);
		sessions.add(client);
		assertThat(client.login(member.getLoginId(), PASSWORD, client.csrf()).statusCode()).isEqualTo(204);
		assertThat(client.sessionId()).isNotBlank();
		var me = client.get("/api/auth/me");
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(json(me).get("id").asLong()).isEqualTo(member.getId());
		return client;
	}

	protected HttpResponse<String> get(String path, String authorization, String sessionId) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(10)).GET();
		if (authorization != null) request.header("Authorization", authorization);
		if (sessionId != null) request.header("Cookie", FarmAuthClient.SESSION_COOKIE + "=" + sessionId);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	protected static String internal(long workerId) {
		return "/api/internal/insurance/workers/" + workerId + "/salary-account";
	}

	protected static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	protected static void noStore(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
	}

	protected static void tokenRejected(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(401);
		noStore(response);
		assertThat(json(response).get("code").asString()).isEqualTo("INVALID_SERVICE_TOKEN");
		assertThat(response.body()).doesNotContain(SERVICE_TOKEN, "bankCode", "registered");
	}

	protected static void account(HttpResponse<String> response, long workerId, String bankCode) {
		assertThat(response.statusCode()).isEqualTo(200);
		noStore(response);
		JsonNode body = json(response);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("workerId", "registered", "bankCode");
		assertThat(body.get("workerId").asLong()).isEqualTo(workerId);
		assertThat(body.get("registered").asBoolean()).isEqualTo(bankCode != null);
		if (bankCode == null) assertThat(body.get("bankCode").isNull()).isTrue();
		else assertThat(body.get("bankCode").asString()).isEqualTo(bankCode);
		assertThat(response.body()).doesNotContain("accountNumber", "accountHolderName", "ciphertext",
			"maskedAccountNumber", "v1:", SERVICE_TOKEN);
	}

	@AfterEach
	void cleanOwnFixtures() {
		try {
			sessions.forEach(FarmAuthClient::close);
			for (Long memberId : fixtureMembers) {
				accounts.deleteById(memberId);
				members.deleteById(memberId);
			}
		} finally {
			http.close();
		}
	}
}

package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class FarmSalaryAccountClientTests {

	private RestClient restClient;
	private MockRestServiceServer server;
	private FarmSalaryAccountClient client;

	@BeforeEach
	void prepare() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://farm.test");
		server = MockRestServiceServer.bindTo(builder).build();
		restClient = builder.build();
		client = new FarmSalaryAccountClient(restClient, properties(true, "test-token"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"020", "004"})
	void queriesTheLinkedWorkerWithOnlyServerAuthentication(String bankCode) {
		server.expect(requestTo("http://farm.test/api/internal/insurance/workers/123/salary-account"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
			.andExpect(header(HttpHeaders.ACCEPT, "application/json"))
			.andExpect(headerDoesNotExist(HttpHeaders.COOKIE))
			.andRespond(withSuccess("{\"workerId\":123,\"registered\":true,\"bankCode\":\"" + bankCode + "\"}", MediaType.APPLICATION_JSON));
		assertThat(client.find(123)).isEqualTo(new SalaryAccountStatus(true, bankCode));
		server.verify();
	}

	@Test
	void unregisteredIsASuccessfulStateNotANotFoundError() {
		server.expect(anything()).andRespond(withSuccess(
			"{\"workerId\":123,\"registered\":false,\"bankCode\":null}", MediaType.APPLICATION_JSON));
		assertThat(client.find(123)).isEqualTo(new SalaryAccountStatus(false, null));
		server.verify();
	}

	@Test
	void unexpectedSensitiveFieldsAreNeverForwarded() {
		server.expect(anything()).andRespond(withSuccess("""
			{"workerId":123,"registered":true,"bankCode":"020","accountNumber":"upstream-private-account","accountHolderName":"private-name"}
			""", MediaType.APPLICATION_JSON));
		assertThat(new ObjectMapper().writeValueAsString(client.find(123)))
			.isEqualTo("{\"registered\":true,\"bankCode\":\"020\"}");
		server.verify();
	}

	@ParameterizedTest
	@MethodSource("invalidResponses")
	void rejectsMissingCoercedAndInconsistentFields(String response) {
		server.expect(anything()).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
		assertFailure(HttpStatus.BAD_GATEWAY, "INVALID_PLATFORM_RESPONSE");
	}

	private static Stream<String> invalidResponses() {
		String valid = "{\"workerId\":123,\"registered\":true,\"bankCode\":\"020\"}";
		return Stream.of(
			valid.replace("\"workerId\":123", "\"workerId\":124"),
			valid.replace("\"workerId\":123", "\"workerId\":\"123\""),
			valid.replace("\"workerId\":123", "\"workerId\":123.5"),
			valid.replace("\"workerId\":123", "\"workerId\":9223372036854775808"),
			valid.replace("\"workerId\":123,", ""),
			valid.replace("\"registered\":true", "\"registered\":\"true\""),
			valid.replace("\"registered\":true", "\"registered\":1"),
			valid.replace("\"registered\":true", "\"registered\":null"),
			valid.replace("\"registered\":true,", ""),
			valid.replace("\"020\"", "20"), valid.replace("\"020\"", "null"),
			valid.replace("\"020\"", "\"20\""), valid.replace("\"020\"", "\"ABC\""),
			valid.replace("\"020\"", "\"\""), valid.replace("\"020\"", "\" 020\""),
			valid.replace(",\"bankCode\":\"020\"", ""),
			valid.replace("\"registered\":true", "\"registered\":false"),
			"{\"workerId\":123,\"registered\":false}", "null", "[]", "{}", "{");
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 503})
	void errorsAreNotConvertedToUnregistered(int status) {
		server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
			.contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"upstream-secret\"}"));
		assertFailure(status == 404 ? HttpStatus.NOT_FOUND : HttpStatus.SERVICE_UNAVAILABLE,
			status == 404 ? "WORKER_NOT_FOUND" : "FARM_SERVICE_UNAVAILABLE");
	}

	@Test
	void timeoutIsUnavailableWithoutLeakingTheUpstreamMessage() {
		server.expect(anything()).andRespond(withException(new SocketTimeoutException("upstream-secret")));
		assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "FARM_SERVICE_UNAVAILABLE");
	}

	@Test
	void emptyAndUnsupportedResponsesAreRejected() {
		server.expect(anything()).andRespond(withSuccess("upstream-secret", MediaType.TEXT_PLAIN));
		assertFailure(HttpStatus.BAD_GATEWAY, "INVALID_PLATFORM_RESPONSE");
	}

	@Test
	void emptyResponseIsNotUnregistered() {
		server.expect(anything()).andRespond(withSuccess());
		assertFailure(HttpStatus.BAD_GATEWAY, "INVALID_PLATFORM_RESPONSE");
	}

	@Test
	void disabledQueryDoesNotContactFarm() {
		client = new FarmSalaryAccountClient(restClient, properties(false, "test-token"));
		assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "SALARY_ACCOUNT_QUERY_NOT_READY");
	}

	@Test
	void missingCredentialDoesNotContactFarm() {
		client = new FarmSalaryAccountClient(restClient, properties(true, ""));
		assertFailure(HttpStatus.SERVICE_UNAVAILABLE, "FARM_SERVICE_UNAVAILABLE");
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void invalidWorkerIdsAreRejectedBeforeNetworkAccess(long workerId) {
		assertThatThrownBy(() -> client.find(workerId)).isInstanceOf(IllegalArgumentException.class);
		server.verify();
	}

	private void assertFailure(HttpStatus status, String code) {
		assertThatThrownBy(() -> client.find(123)).isInstanceOfSatisfying(PlatformVerificationException.class, exception -> {
			assertThat(exception.getStatus()).isEqualTo(status);
			assertThat(exception.getCode()).isEqualTo(code);
			assertThat(exception.getMessage()).doesNotContain("upstream-secret", "test-token");
		});
		server.verify();
	}

	private static FarmPlatformProperties properties(boolean enabled, String token) {
		return new FarmPlatformProperties("http://farm.test", token, "farm-platform-v1", false, enabled);
	}
}

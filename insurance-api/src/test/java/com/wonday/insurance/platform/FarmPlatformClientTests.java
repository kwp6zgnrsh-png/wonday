package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FarmPlatformClientTests {

	private MockRestServiceServer server;
	private FarmPlatformClient client;

	@BeforeEach
	void prepare() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://farm.test");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new FarmPlatformClient(builder.build(),
			new FarmPlatformProperties("http://farm.test", "test-service-token", "farm-platform-v1"));
	}

	@Test
	void sendsNormalizedIdentityAndBearerTokenToAgreedEndpoint() {
		server.expect(requestTo("http://farm.test/api/internal/insurance/worker-verifications"))
			.andExpect(method(org.springframework.http.HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{"name":"테스트 근로자","phone":"01000000000","residentRegistrationNumber":"0000000000000"}
				"""))
			.andRespond(withSuccess("""
				{"workerId":77,"name":"테스트 근로자","phone":"01000000000"}
				""", MediaType.APPLICATION_JSON));
		assertThat(client.verify(identity()).workerId()).isEqualTo(77);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 409, 401, 403, 500, 503})
	void mapsFarmFailuresWithoutExposingUpstreamBody(int status) {
		server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
			.contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"upstream-secret\"}"));
		int expected = switch (status) { case 400, 404, 409 -> status; default -> 503; };
		assertThatThrownBy(() -> client.verify(identity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getStatus().value()).isEqualTo(expected);
				assertThat(exception.getMessage()).doesNotContain("upstream-secret");
			});
	}

	@Test
	void timeoutHasAnAvailabilityError() {
		server.expect(anything()).andRespond(withException(new SocketTimeoutException("upstream-secret")));
		assertThatThrownBy(() -> client.verify(identity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception ->
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void malformedAndMismatchedSuccessCannotLinkAnAccount() {
		server.expect(anything()).andRespond(withSuccess("""
			{"workerId":77,"name":"another worker","phone":"01000000000"}
			""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.verify(identity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception ->
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@Test
	void missingServiceCredentialIsRejectedBeforeSendingIdentity() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer noCalls = MockRestServiceServer.bindTo(builder).build();
		FarmPlatformClient unconfigured = new FarmPlatformClient(builder.build(),
			new FarmPlatformProperties("http://farm.test", "", "farm-platform-v1"));
		assertThatThrownBy(() -> unconfigured.verify(identity())).isInstanceOf(PlatformVerificationException.class);
		noCalls.verify();
	}

	private static WorkerIdentity identity() {
		return new WorkerIdentity(" 테스트 근로자 ", "010-0000-0000", "000000-0000000");
	}
}

package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import com.wonday.insurance.member.NationalityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FarmPlatformClientTests {

	private MockRestServiceServer server;
	private FarmPlatformClient client;
	private RestClient restClient;

	@BeforeEach
	void prepare() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://farm.test");
		server = MockRestServiceServer.bindTo(builder).build();
		restClient = builder.build();
		client = new FarmPlatformClient(restClient,
			new FarmPlatformProperties("http://farm.test", "test-service-token", "farm-platform-v1"));
	}

	@ParameterizedTest
	@EnumSource(NationalityType.class)
	void v2SendsBothNationalitiesAndRequiresMatchingResponse(NationalityType nationality) {
		enableV2();
		server.expect(requestTo("http://farm.test/api/internal/insurance/v2/worker-verifications"))
			.andExpect(method(org.springframework.http.HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{"name":"TEST WORKER","phone":"01000000000","nationalityType":"%s","identityNumber":"0000000000000"}
				""".formatted(nationality)))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("residentRegistrationNumber"))))
			.andRespond(withSuccess("""
				{"workerId":77,"name":"TEST WORKER","phone":"01000000000","nationalityType":"%s"}
				""".formatted(nationality), MediaType.APPLICATION_JSON));
		var worker = client.verify(new WorkerIdentity(" TEST WORKER ", "010-0000-0000", nationality, "000000-0000000"));
		assertThat(worker.workerId()).isEqualTo(77);
		assertThat(worker.nationalityType()).isEqualTo(nationality);
		assertThat(worker.toString()).doesNotContain("TEST WORKER", "01000000000");
		server.verify();
	}

	@Test
	void legacyModeRejectsForeignIdentityBeforeAnyRequest() {
		assertThatThrownBy(() -> client.verify(foreignIdentity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(exception.getCode()).isEqualTo("FOREIGN_VERIFICATION_NOT_READY");
			});
		server.verify();
	}

	@ParameterizedTest
	@MethodSource("invalidV2Responses")
	void malformedOrMismatchedV2ResponsesCannotVerifyAWorker(String body) {
		enableV2();
		server.expect(requestTo("http://farm.test/api/internal/insurance/v2/worker-verifications"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.verify(foreignIdentity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
				assertThat(exception.getCode()).isEqualTo("INVALID_PLATFORM_RESPONSE");
				assertThat(exception.getMessage()).doesNotContain("upstream-secret");
			});
		server.verify();
	}

	private static Stream<String> invalidV2Responses() {
		String valid = "{\"workerId\":77,\"name\":\"TEST WORKER\",\"phone\":\"01000000000\",\"nationalityType\":\"FOREIGN\"}";
		return Stream.of(
			valid.replace("\"workerId\":77", "\"workerId\":\"77\""),
			valid.replace("\"workerId\":77", "\"workerId\":77.5"),
			valid.replace("\"workerId\":77", "\"workerId\":9223372036854775808"),
			valid.replace("\"workerId\":77", "\"workerId\":0"),
			valid.replace("\"workerId\":77", "\"workerId\":-1"),
			valid.replace("\"workerId\":77", "\"workerId\":true"),
			valid.replace("\"TEST WORKER\"", "7"),
			valid.replace("\"TEST WORKER\"", "\"upstream-secret\""),
			valid.replace("\"01000000000\"", "10000000000"),
			valid.replace("\"01000000000\"", "\"01099999999\""),
			valid.replace(",\"nationalityType\":\"FOREIGN\"", ""),
			valid.replace("\"FOREIGN\"", "null"),
			valid.replace("\"FOREIGN\"", "\"DOMESTIC\""),
			valid.replace("\"FOREIGN\"", "\"UNKNOWN\""),
			valid.replace("\"FOREIGN\"", "0"),
			"{}", "[]", "null");
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 409, 401, 403, 500, 503})
	void v2FailuresNeverFallBackToTheLegacyEndpoint(int status) {
		enableV2();
		server.expect(requestTo("http://farm.test/api/internal/insurance/v2/worker-verifications"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"message\":\"upstream-secret\"}"));
		int expected = switch (status) { case 400, 404, 409 -> status; default -> 503; };
		assertThatThrownBy(() -> client.verify(foreignIdentity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getStatus().value()).isEqualTo(expected);
				assertThat(exception.getMessage()).doesNotContain("upstream-secret");
			});
		server.verify();
	}

	@Test
	void v2AlsoRequiresServiceCredentialsBeforeSendingIdentity() {
		client = new FarmPlatformClient(restClient, new FarmPlatformProperties("http://farm.test", "", "v1", true));
		assertThatThrownBy(() -> client.verify(foreignIdentity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> assertThat(exception.getCode()).isEqualTo("FARM_SERVICE_UNAVAILABLE"));
		server.verify();
	}

	@Test
	void v2TimeoutDoesNotExposeTheUpstreamError() {
		enableV2();
		server.expect(anything()).andRespond(withException(new SocketTimeoutException("upstream-secret")));
		assertThatThrownBy(() -> client.verify(foreignIdentity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getCode()).isEqualTo("FARM_SERVICE_UNAVAILABLE");
				assertThat(exception.getMessage()).doesNotContain("upstream-secret");
			});
		server.verify();
	}

	private void enableV2() {
		client = new FarmPlatformClient(restClient,
			new FarmPlatformProperties("http://farm.test", "test-service-token", "farm-platform-v1", true));
	}

	private static WorkerIdentity foreignIdentity() {
		return new WorkerIdentity("TEST WORKER", "01000000000", NationalityType.FOREIGN, "0000000000000");
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
	void mismatchedIdentityInSuccessCannotLinkAnAccount() {
		server.expect(anything()).andRespond(withSuccess("""
			{"workerId":77,"name":"another worker","phone":"01000000000"}
			""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.verify(identity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception ->
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{",
		"{\"workerId\":{},\"name\":\"upstream-secret\",\"phone\":\"01000000000\"}",
		"{\"workerId\":77,\"name\":{},\"phone\":\"01000000000\"}"
	})
	void undecodableSuccessHasAnInvalidResponseError(String body) {
		server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertInvalidResponse();
	}

	@Test
	void unsupportedSuccessContentTypeHasAnInvalidResponseError() {
		server.expect(anything()).andRespond(withSuccess("upstream-secret", MediaType.TEXT_PLAIN));
		assertInvalidResponse();
	}

	@Test
	void emptySuccessHasAnInvalidResponseError() {
		server.expect(anything()).andRespond(withSuccess());
		assertInvalidResponse();
	}

	private void assertInvalidResponse() {
		assertThatThrownBy(() -> client.verify(identity())).isInstanceOfSatisfying(
			PlatformVerificationException.class, exception -> {
				assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
				assertThat(exception.getCode()).isEqualTo("INVALID_PLATFORM_RESPONSE");
				assertThat(exception.getMessage()).doesNotContain("upstream-secret");
			});
		server.verify();
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

package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class FarmWorkClientTests {
	private ValidatorFactory validators;
	private MockRestServiceServer server;
	private FarmWorkClient client;
	private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

	@BeforeEach
	void prepare() {
		validators = Validation.buildDefaultValidatorFactory();
		RestClient.Builder builder = RestClient.builder().baseUrl("http://farm.test");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new FarmWorkClient(builder.build(),
			new FarmPlatformProperties("http://farm.test","test-token","v1"),validators.getValidator());
	}

	@AfterEach
	void close() { validators.close(); }

	@Test
	void sendsBearerTokenAndEncodesOpaqueCursorAsOneQueryValue() {
		server.expect(requestTo("http://farm.test/api/internal/insurance/workers/123/work-changes?cursor=a%2Bb%2F%3D%26%3F&limit=100"))
			.andExpect(method(HttpMethod.GET)).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer test-token"))
			.andRespond(withSuccess(pageJson(),MediaType.APPLICATION_JSON));
		assertThat(client.changes(123,"a+b/=&?",100).items()).hasSize(1);
		server.verify();
	}

	@Test
	void detailUsesWorkerScopedPathAndRetainsOffsetAndBreakTimes() {
		server.expect(requestTo("http://farm.test/api/internal/insurance/workers/123/work-assignments/7001"))
			.andRespond(withSuccess(json.writeValueAsString(WorkChangePollingTests.assignment(7001)),MediaType.APPLICATION_JSON));
		assertThat(client.assignment(123,7001).agreedBreakStartAt()).isNotNull();
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(ints = {400,401,403,404,500,503})
	void failuresDoNotExposeUpstreamBody(int status) {
		server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body("secret token"));
		assertThatThrownBy(() -> client.changes(123,null,100))
			.isInstanceOf(PlatformVerificationException.class).hasMessageNotContaining("secret");
	}

	@Test
	void timeoutCanBeRetriedWithSameCursor() {
		server.expect(anything()).andRespond(withException(new SocketTimeoutException("secret")));
		server.expect(anything()).andRespond(withSuccess(pageJson(),MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.changes(123,"c1",100)).isInstanceOf(PlatformVerificationException.class);
		assertThat(client.changes(123,"c1",100).nextCursor()).isEqualTo("c2");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "{}", "{\"workerId\":123,\"items\":[],\"nextCursor\":\"c1\"}",
		"{\"workerId\":999,\"items\":[],\"nextCursor\":\"c1\",\"hasMore\":false}",
		"{\"workerId\":123,\"items\":[null],\"nextCursor\":\"c1\",\"hasMore\":false}",
		"{\"workerId\":123,\"items\":[],\"nextCursor\":\"c1\",\"hasMore\":true}"})
	void malformedOrNonProgressingPageIsRejected(String body) {
		server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> client.changes(123,"c1",100)).isInstanceOf(PlatformVerificationException.class);
	}

	@Test
	void mismatchedDetailAndInvalidTimeZoneAreRejected() {
		String valid = json.writeValueAsString(WorkChangePollingTests.assignment(7001));
		for (String body : List.of(valid.replace("\"workerId\":123","\"workerId\":999"),
			valid.replace("GMT+9","invalid-zone"), valid.replace("\"agreedDailyWage\":120000","\"agreedDailyWage\":0"))) {
			server.reset();
			server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
			assertThatThrownBy(() -> client.assignment(123,7001)).isInstanceOf(PlatformVerificationException.class);
		}
	}

	@Test
	void unconfiguredTokenMakesNoRequest() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer noCalls = MockRestServiceServer.bindTo(builder).build();
		FarmWorkClient unconfigured = new FarmWorkClient(builder.build(),
			new FarmPlatformProperties("http://farm.test","","v1"),validators.getValidator());
		assertThatThrownBy(() -> unconfigured.changes(123,null,100)).isInstanceOf(PlatformVerificationException.class);
		noCalls.verify();
	}

	private String pageJson() {
		return json.writeValueAsString(WorkChangePollingTests.page("c2",false,
			WorkChangePollingTests.change(9001,"1",7001)));
	}
}

package com.wonday.insurance.weather;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RdaWeatherClientTests {
	private static final LocalDate DATE = LocalDate.of(2026, 11, 25);
	private static final String CODE = "477802A001";
	private MockRestServiceServer server;
	private RdaWeatherClient client;
	@BeforeEach void setup() {
		var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build();
		client = new RdaWeatherClient(builder.build(), new WeatherProperties("https://weather.test", "raw+/=key"));
	}
	@Test void preservesEveryOriginalFieldAndMidnightWithoutInterpretingTheRainfall() {
		String xml = page(1, 1, item(CODE, "24:00", "<rn>12.3</rn><unknown_sensor>X</unknown_sensor>"));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("serviceKey=raw%2B%2F%3Dkey")))
			.andRespond(withSuccess(xml, MediaType.APPLICATION_XML));
		var result = client.observations(CODE, DATE);
		assertThat(result.itemCount()).isEqualTo(1); assertThat(result.pages()).containsExactly(xml);
		server.verify();
	}
	@Test void readsAllPagesAndRejectsPartialOrChangedTotals() {
		server.expect(anything()).andRespond(withSuccess(page(1, 2, item(CODE,"00:10","")), MediaType.APPLICATION_XML));
		server.expect(anything()).andRespond(withSuccess(page(2, 2, item(CODE,"00:20","")), MediaType.APPLICATION_XML));
		assertThat(client.observations(CODE, DATE).itemCount()).isEqualTo(2); server.verify();
	}
	@ParameterizedTest @ValueSource(strings={"wrong-station","wrong-date","duplicate","wrong-page","changed-total","empty-page","invalid-xml","xxe","provider-rejected"})
	void invalidResponsesAreRejectedWithoutLeakingTheKey(String kind) {
		String valid = item(CODE, "00:10", "");
		String xml = switch(kind) {
			case "wrong-station" -> page(1,1,item("other","00:10",""));
			case "wrong-date" -> page(1,1,valid.replace("2026-11-25","2026-11-24"));
			case "duplicate" -> page(1,2,valid+valid);
			case "wrong-page" -> page(2,1,valid);
			case "empty-page" -> page(1,1,"");
			case "invalid-xml" -> "not XML raw+/=key";
			case "xxe" -> "<!DOCTYPE response [<!ENTITY x SYSTEM 'file:///not-readable'>]><response>&x;</response>";
			case "provider-rejected" -> "<response><header><result_Code>101</result_Code><result_Msg>raw+/=key</result_Msg></header></response>";
			default -> page(1,2,valid);
		};
		server.expect(anything()).andRespond(withSuccess(xml, MediaType.APPLICATION_XML));
		if (kind.equals("changed-total")) server.expect(anything()).andRespond(withSuccess(page(2,3,item(CODE,"00:20","")),MediaType.APPLICATION_XML));
		assertThatThrownBy(() -> client.observations(CODE,DATE)).isInstanceOf(WeatherException.class).hasMessageNotContaining("raw+/=key");
		server.verify();
	}
	@Test void emptyProviderResultIsExplicitlyRetained() {
		server.expect(anything()).andRespond(withSuccess("<response><header><result_Code>301</result_Code></header></response>",MediaType.APPLICATION_XML));
		assertThat(client.observations(CODE,DATE).itemCount()).isZero(); server.verify();
	}
	@ParameterizedTest @ValueSource(ints={429,500,503})
	void retriesTransientHttpFailuresOnlyUpToThreeAttempts(int status) {
		for(int i=0;i<2;i++) server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)));
		server.expect(anything()).andRespond(withSuccess(page(1,1,item(CODE,"00:10","")),MediaType.APPLICATION_XML));
		assertThat(client.observations(CODE,DATE).itemCount()).isEqualTo(1); server.verify();
	}
	@Test void permanentRejectionIsNotRetried() {
		server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> client.observations(CODE,DATE)).isInstanceOfSatisfying(WeatherException.class,
			e -> assertThat(e.getCode()).isEqualTo("WEATHER_PROVIDER_REJECTED")); server.verify();
	}
	@Test void exhaustedRetriesAreSanitized() {
		for(int i=0;i<3;i++) server.expect(anything()).andRespond(withServerError());
		assertThatThrownBy(() -> client.observations(CODE,DATE)).isInstanceOfSatisfying(WeatherException.class,
			e -> { assertThat(e.getCode()).isEqualTo("WEATHER_SERVICE_UNAVAILABLE"); assertThat(e.getCause()).isNull(); }); server.verify();
	}
	@Test void anEncodedKeyIsDecodedOnceAndThenEscapedAsAQueryValue() {
		var builder=RestClient.builder(); server=MockRestServiceServer.bindTo(builder).build();
		client=new RdaWeatherClient(builder.build(),new WeatherProperties("https://weather.test","raw%2B%2F%3Dkey"));
		server.expect(requestTo(org.hamcrest.Matchers.containsString("serviceKey=raw%2B%2F%3Dkey")))
			.andRespond(withSuccess(page(1,1,item(CODE,"00:10","")),MediaType.APPLICATION_XML));
		client.observations(CODE,DATE); server.verify();
	}
	@Test void missingKeyDoesNotMakeAnExternalRequest() {
		client=new RdaWeatherClient(RestClient.builder().build(),new WeatherProperties("https://weather.test",""));
		assertThatThrownBy(() -> client.observations(CODE,DATE)).isInstanceOfSatisfying(WeatherException.class,
			e -> assertThat(e.getCode()).isEqualTo("WEATHER_API_NOT_CONFIGURED"));
	}
	@Test void scopeAndDistanceAreDeterministic() throws Exception {
		var scope=new GyeonggiStations(); assertThat(scope.names()).hasSize(24).containsKey("017414F020");
		assertThat(StationCoordinates.distanceMeters(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)).isZero();
		assertThat(StationCoordinates.distanceMeters(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,BigDecimal.ZERO)).isBetween(111_000.0,112_000.0);
		assertThatThrownBy(() -> scope.require("not-a-station")).isInstanceOf(WeatherException.class);
		assertThatThrownBy(() -> StationCoordinates.validate(BigDecimal.valueOf(91),BigDecimal.ZERO)).isInstanceOf(WeatherException.class);
	}
	static String item(String code,String time,String data) {
		return "<item><stn_Cd>"+code+"</stn_Cd><date>2026-11-25 "+time+"</date>"+data+"</item>";
	}
	static String page(int number,int total,String items) {
		return "<response><header><result_Code>200</result_Code></header><body><page_No>"+number+"</page_No>"
			+"<total_Count>"+total+"</total_Count><items>"+items+"</items></body></response>";
	}
}

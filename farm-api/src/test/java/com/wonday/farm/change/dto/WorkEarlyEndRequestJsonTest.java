package com.wonday.farm.change.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 요청 DTO + EarlyEndTimeDeserializer·EarlyEndStringDeserializer. 기본 JsonMapper이며 Boot 구성 JsonMapper와 같다고 증명하지 않는다.
@DisplayName("CHANGE-03 조기 종료 요청 JSON 역직렬화")
class WorkEarlyEndRequestJsonTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static WorkEarlyEndRequest read(String json) { return JSON.readValue(json, WorkEarlyEndRequest.class); }
	private static WorkEarlyEndRequest time(String value) {
		return read("{\"actualEndAt\":\"" + value + "\",\"reasonCode\":\"OTHER\"}");
	}

	@Test
	void exampleRequest() {
		var request = read("{\"actualEndAt\":\"2026-12-02T14:00:00.123456+09:00\",\"reasonCode\":\"WEATHER\","
			+ "\"weatherReasonCode\":\"RAIN\",\"reasonDetail\":\"  강수로 작업을 중단했습니다.  \"}");
		assertThat(request.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-12-02T14:00:00.123456+09:00"));
		assertThat(request.reasonCode()).isEqualTo("WEATHER");
		assertThat(request.weatherReasonCode()).isEqualTo("RAIN");
		assertThat(request.reasonDetail()).isEqualTo("  강수로 작업을 중단했습니다.  ");
	}

	@ParameterizedTest
	@CsvSource({
		"2026-12-02T14:00:00+09:00, 2026-12-02T05:00:00Z",
		"2026-12-02T14:00+09:00, 2026-12-02T05:00:00Z",
		"2026-12-02T05:00:00Z, 2026-12-02T05:00:00Z",
		"2026-12-02T05:00Z, 2026-12-02T05:00:00Z",
		"2026-12-01T21:00:00-08:00, 2026-12-02T05:00:00Z",
		"2026-12-02T10:30:00+05:30, 2026-12-02T05:00:00Z",
		"2026-12-02T05:00:00+00:00, 2026-12-02T05:00:00Z",
		"2026-12-02T14:00:00.1+09:00, 2026-12-02T05:00:00.1Z",
		"2026-12-02T14:00:00.12+09:00, 2026-12-02T05:00:00.12Z",
		"2026-12-02T14:00:00.123+09:00, 2026-12-02T05:00:00.123Z",
		"2026-12-02T14:00:00.123456+09:00, 2026-12-02T05:00:00.123456Z",
		"2026-12-02T14:00:00.000000+09:00, 2026-12-02T05:00:00Z",
		"2024-02-29T14:00:00+09:00, 2024-02-29T05:00:00Z"
	})
	void acceptedFormatsAreReadAsTheSameInstant(String input, String instant) {
		var value = time(input).actualEndAt();
		assertThat(value.toInstant()).isEqualTo(java.time.Instant.parse(instant));
		// Z는 UTC 오프셋으로 유지하고 서버 기본 시간대로 바꾸지 않는다.
		if (input.endsWith("Z")) assertThat(value.getOffset()).isEqualTo(java.time.ZoneOffset.UTC);
		if (input.endsWith("+05:30")) assertThat(value.getOffset().getTotalSeconds()).isEqualTo(19800);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"2026-12-02T14:00:00.1234567+09:00", "2026-12-02T14:00:00.1234560+09:00", "2026-12-02T14:00:00.000000000+09:00",
		"2026-12-02T14:00:00", "2026-12-02T14:00", "2026-12-02 14:00:00+09:00", "2026-12-02t14:00:00+09:00",
		"2026-12-02T14:00:00z", "2026-12-02T14:00:00+0900", "2026-12-02T14:00:00+09", "2026-12-02T14:00:00+09:00:00",
		"2026-12-02T14:00:00+09:00[Asia/Seoul]", "2026-12-02T14:00:00 +09:00", " 2026-12-02T14:00:00+09:00",
		"2026-12-02T14:00:00+09:00 ", "2026-02-30T14:00:00+09:00", "2025-02-29T14:00:00+09:00", "2026-13-01T14:00:00+09:00",
		"2026-12-02T24:00:00+09:00", "2026-12-02T14:60:00+09:00", "2026-12-02T14:00:60+09:00", "2026-12-02T14:00:00+19:00",
		"2026-12-02T14:00:00.+09:00", "2026-12-02T14:00:00,123+09:00", "+2026-12-02T14:00:00+09:00", "2026-12-2T14:00:00+09:00",
		"2026-12-02", "14:00:00+09:00", "", "now", "1764651600"})
	void rejectedStrings(String value) {
		assertThatThrownBy(() -> time(value)).as(value).isInstanceOf(JacksonException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"1764651600", "1764651600.5", "true", "false", "[]", "[2026,12,2,14,0]", "{}", "{\"value\":\"2026-12-02T14:00:00+09:00\"}"})
	void nonStringTimeTokensRejected(String token) {
		assertThatThrownBy(() -> read("{\"actualEndAt\":" + token + ",\"reasonCode\":\"OTHER\"}")).as(token).isInstanceOf(JacksonException.class);
	}

	@Test
	void nullOrMissingTimeIsNullForLaterValidation() {
		assertThat(read("{\"actualEndAt\":null,\"reasonCode\":\"OTHER\"}").actualEndAt()).isNull();
		assertThat(read("{\"reasonCode\":\"OTHER\"}").actualEndAt()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"1", "1.5", "true", "[]", "[\"OTHER\"]", "{}"})
	void stringFieldsAreNotCoerced(String token) {
		for (var field : new String[] {"reasonCode", "weatherReasonCode", "reasonDetail"}) {
			String json = "{\"actualEndAt\":\"2026-12-02T14:00:00+09:00\",\"reasonCode\":\"OTHER\",\"" + field + "\":" + token + "}";
			assertThatThrownBy(() -> read(json)).as(json).isInstanceOf(JacksonException.class);
		}
	}
}

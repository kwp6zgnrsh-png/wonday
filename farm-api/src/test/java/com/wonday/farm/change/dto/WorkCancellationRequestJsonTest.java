package com.wonday.farm.change.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 요청 DTO + CancellationStringDeserializer. 기본 JsonMapper이며 Spring Boot가 구성한 JsonMapper 설정과 같다고 증명하지 않는다.
@DisplayName("CHANGE-01·02 취소 요청 JSON 역직렬화 (문자열만 허용)")
class WorkCancellationRequestJsonTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static WorkCancellationRequest read(String json) { return JSON.readValue(json, WorkCancellationRequest.class); }

	@Test
	void stringsAreReadAsIs() {
		var request = read("{\"reasonCode\":\"WEATHER\",\"weatherReasonCode\":\"RAIN\",\"reasonDetail\":\"  비\\n옴  \"}");
		assertThat(request).isEqualTo(new WorkCancellationRequest("WEATHER", "RAIN", "  비\n옴  "));
	}

	@Test
	void omittedAndNullFieldsAreNull() {
		assertThat(read("{\"reasonCode\":\"OTHER\"}")).isEqualTo(new WorkCancellationRequest("OTHER", null, null));
		assertThat(read("{\"reasonCode\":\"OTHER\",\"weatherReasonCode\":null,\"reasonDetail\":null}"))
			.isEqualTo(new WorkCancellationRequest("OTHER", null, null));
		assertThat(read("{}")).isEqualTo(new WorkCancellationRequest(null, null, null));
	}

	@ParameterizedTest
	@ValueSource(strings = {"1", "0", "1.5", "-2", "1e3", "true", "false", "[]", "[\"WEATHER\"]", "{}", "{\"value\":\"WEATHER\"}"})
	void nonStringTokensAreNotCoercedForAnyField(String token) {
		for (var field : new String[] {"reasonCode", "weatherReasonCode", "reasonDetail"}) {
			String json = "{\"reasonCode\":\"OTHER\",\"" + field + "\":" + token + "}";
			assertThatThrownBy(() -> read(json)).as(json).isInstanceOf(JacksonException.class);
		}
	}

	@Test
	void malformedJsonFails() {
		for (var json : new String[] {"", "{", "{\"reasonCode\":}", "[]", "\"OTHER\"", "{\"reasonCode\":\"OTHER\",}"}) {
			assertThatThrownBy(() -> read(json)).as(json).isInstanceOf(JacksonException.class);
		}
	}
}

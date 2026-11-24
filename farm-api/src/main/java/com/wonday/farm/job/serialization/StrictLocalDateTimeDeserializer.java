package com.wonday.farm.job.serialization;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 공고의 현지 일시는 문자열로 받고 날짜와 시간 사이의 대문자 T를 검사한다.
public class StrictLocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {
	@Override
	public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, parser);
		}
		String value = parser.getString();
		if (value.length() <= 10 || value.charAt(10) != 'T') {
			return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, value,
				"날짜와 시간은 대문자 T로 구분해 주세요.");
		}
		try {
			// LocalDateTime의 ISO 파서는 Z와 오프셋을 받지 않고 실제 달력 날짜도 검사한다.
			return LocalDateTime.parse(value);
		} catch (DateTimeParseException exception) {
			return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, value,
				"시간대 접미사 없는 ISO 현지 일시 문자열을 입력해 주세요.");
		}
	}
}

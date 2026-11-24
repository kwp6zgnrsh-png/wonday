package com.wonday.farm.job;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 근무일은 ISO 날짜 문자열만 받는다. 날짜 배열과 숫자 입력은 거절한다.
public class StrictLocalDateDeserializer extends ValueDeserializer<LocalDate> {
	@Override
	public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
		}
		String value = parser.getString();
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			return (LocalDate) context.handleWeirdStringValue(LocalDate.class, value,
				"근무일은 yyyy-MM-dd 형식의 날짜 문자열이어야 합니다.");
		}
	}
}

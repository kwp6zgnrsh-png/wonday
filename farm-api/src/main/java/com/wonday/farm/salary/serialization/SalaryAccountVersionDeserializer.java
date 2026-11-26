package com.wonday.farm.salary.serialization;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 버전은 JSON 정수만 받는다. 소수 절단·숫자 문자열 변환·Long 범위 초과를 허용하지 않는다.
public class SalaryAccountVersionDeserializer extends ValueDeserializer<Long> {
	@Override
	public Long deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return (Long) context.handleUnexpectedToken(Long.class, parser);
		}
		return parser.getLongValue();
	}
}

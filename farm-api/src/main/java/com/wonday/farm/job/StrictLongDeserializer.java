package com.wonday.farm.job;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 공고의 일급은 JSON 정수만 받는다. Long 범위 초과도 JSON 입력 오류로 처리한다.
public class StrictLongDeserializer extends ValueDeserializer<Long> {
	@Override
	public Long deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return (Long) context.handleUnexpectedToken(Long.class, parser);
		}
		return parser.getLongValue();
	}
}

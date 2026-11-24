package com.wonday.farm.job;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 공고의 정원은 JSON 정수만 받는다. 소수·문자열 입력의 자동 변환을 차단한다.
public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {
	@Override
	public Integer deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
			return (Integer) context.handleUnexpectedToken(Integer.class, parser);
		}
		return parser.getIntValue();
	}
}

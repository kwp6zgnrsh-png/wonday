package com.wonday.farm.salary.serialization;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 앞자리 0을 지키고 숫자·boolean 등의 자동 문자열 변환을 막는다.
public class SalaryAccountStringDeserializer extends ValueDeserializer<String> {
	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (String) context.handleUnexpectedToken(String.class, parser);
		}
		return parser.getString();
	}
}

package com.wonday.farm.insurance.serialization;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 주민번호와 전화번호의 앞자리 0을 보존하도록 JSON 문자열만 허용한다.
public class IdentityStringDeserializer extends ValueDeserializer<String> {
	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (String) context.handleUnexpectedToken(String.class, parser);
		}
		return parser.getString();
	}
}

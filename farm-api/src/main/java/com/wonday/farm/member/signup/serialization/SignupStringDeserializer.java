package com.wonday.farm.member.signup.serialization;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 식별번호·계좌·구분값에 숫자/boolean 자동 변환을 허용하지 않는다. 앞자리 0도 보존한다.
public class SignupStringDeserializer extends ValueDeserializer<String> {
	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_STRING)) {
			return (String) context.handleUnexpectedToken(String.class, parser);
		}
		return parser.getString();
	}
}

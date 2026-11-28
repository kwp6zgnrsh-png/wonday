package com.wonday.farm.change.serialization;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 조기 종료 요청에만 적용한다. 숫자·불리언·배열 등을 문자열로 강제 변환하지 않는다.
public class EarlyEndStringDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getString();
    }
}

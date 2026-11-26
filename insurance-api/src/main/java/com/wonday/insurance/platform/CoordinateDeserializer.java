package com.wonday.insurance.platform;

import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 좌표는 JSON 숫자로만 받는다. 숫자 문자열이나 빈 문자열을 좌표·누락값으로 자동 변환하지 않는다. */
public final class CoordinateDeserializer extends ValueDeserializer<BigDecimal> {
	@Override
	public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
			return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
		}
		return parser.getDecimalValue();
	}
}

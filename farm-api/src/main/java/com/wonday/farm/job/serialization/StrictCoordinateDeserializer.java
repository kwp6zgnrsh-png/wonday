package com.wonday.farm.job.serialization;

import java.math.BigDecimal;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 좌표는 JSON 숫자만 받는다. 원본 소수 값을 유지해 범위 검사 전에 반올림되지 않게 한다.
public class StrictCoordinateDeserializer extends ValueDeserializer<BigDecimal> {
	@Override
	public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
		if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
			return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
		}
		return parser.getDecimalValue();
	}
}

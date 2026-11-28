package com.wonday.farm.change.serialization;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

// 날짜·시각·오프셋을 명시한 문자열만 받는다. 초는 생략할 수 있고, 소수초를 보내면 1~6자리만 허용한다.
public class EarlyEndTimeDeserializer extends ValueDeserializer<OffsetDateTime> {
    private static final Pattern FORMAT = Pattern.compile(
        "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,6})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})");
    private static final DateTimeFormatter PARSER = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (OffsetDateTime) context.handleUnexpectedToken(OffsetDateTime.class, parser);
        }
        String value = parser.getString();
        if (FORMAT.matcher(value).matches()) {
            try {
                // Z도 UTC 오프셋으로 해석한다. 접미사를 삭제하거나 서버 기본 시간대로 추측하지 않는다.
                return OffsetDateTime.parse(value, PARSER);
            } catch (DateTimeParseException exception) {
                // 형식뿐 아니라 실제 달력 날짜·시각·오프셋의 유효성도 검사한다.
            }
        }
        return (OffsetDateTime) context.handleWeirdStringValue(OffsetDateTime.class, value,
            "오프셋 포함 ISO 8601 일시를 입력해 주세요. 소수초는 최대 6자리입니다.");
    }
}

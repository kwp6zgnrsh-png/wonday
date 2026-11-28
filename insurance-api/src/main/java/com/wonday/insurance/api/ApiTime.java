package com.wonday.insurance.api;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 응답 일시만 GMT+9로 변환한다. DB의 시간대 없는 일시는 기존 저장 규칙인 UTC로 해석한다. */
public final class ApiTime {
	public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
	private ApiTime() {}

	public static OffsetDateTime fromUtc(LocalDateTime utc) {
		// atOffset(+09:00)을 바로 붙이면 실제 순간이 9시간 달라진다. UTC를 붙인 뒤 같은 순간으로 변환한다.
		return utc == null ? null : utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(OFFSET);
	}

	public static OffsetDateTime fromInstant(Instant instant) {
		return instant == null ? null : instant.atOffset(OFFSET);
	}

	public static String fromOffsetString(String value) {
		return value == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
			OffsetDateTime.parse(value).withOffsetSameInstant(OFFSET));
	}
}

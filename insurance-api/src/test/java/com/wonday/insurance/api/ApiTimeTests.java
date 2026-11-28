package com.wonday.insurance.api;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class ApiTimeTests {
	@Test void utcStorageTimeIsConvertedWithoutChangingTheInstantOrPrecision() {
		var stored = LocalDateTime.parse("2026-11-28T18:00:00.123456");
		var response = ApiTime.fromUtc(stored);
		assertThat(response).isEqualTo(OffsetDateTime.parse("2026-11-29T03:00:00.123456+09:00"));
		assertThat(response.toInstant()).isEqualTo(stored.toInstant(ZoneOffset.UTC));
		assertThat(stored).isEqualTo(LocalDateTime.parse("2026-11-28T18:00:00.123456"));
	}
	@Test void utcAndOtherOffsetsNormalizeToTheSameResponse() {
		for (String source : new String[]{"2026-11-28T06:00:00Z", "2026-11-28T01:00:00-05:00", "2026-11-28T15:00:00+09:00"}) {
			assertThat(ApiTime.fromOffsetString(source)).isEqualTo("2026-11-28T15:00:00+09:00");
		}
	}
	@Test void nullOptionalTimesRemainNull() {
		assertThat(ApiTime.fromUtc(null)).isNull(); assertThat(ApiTime.fromOffsetString(null)).isNull(); assertThat(ApiTime.fromInstant(null)).isNull();
	}
	@Test void yearAndLeapDayBoundariesFollowTheSameInstant() {
		assertThat(ApiTime.fromUtc(LocalDateTime.parse("2026-12-31T23:00:00")))
			.isEqualTo(OffsetDateTime.parse("2027-01-01T08:00:00+09:00"));
		assertThat(ApiTime.fromOffsetString("2024-02-28T23:30:00Z")).isEqualTo("2024-02-29T08:30:00+09:00");
	}
	@Test void processDefaultTimeZoneCannotShiftApiTimes() {
		var previous=TimeZone.getDefault();
		try {
			TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
			assertThat(ApiTime.fromUtc(LocalDateTime.parse("2026-03-08T02:00:00")))
				.isEqualTo(OffsetDateTime.parse("2026-03-08T11:00:00+09:00"));
		} finally { TimeZone.setDefault(previous); }
	}
}

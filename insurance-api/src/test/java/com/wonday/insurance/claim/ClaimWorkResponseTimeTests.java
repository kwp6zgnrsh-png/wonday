package com.wonday.insurance.claim;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class ClaimWorkResponseTimeTests {
	private final ObjectMapper json = new ObjectMapper();
	@Test void onlyKnownTimesChangeInAnIndependentResponseCopy() {
		var original = json.createObjectNode().put("confirmedAt", "2026-11-28T18:00:00.123456Z")
			.put("agreedWorkDate", "2026-11-28").put("agreedTimeZone", "GMT+9")
			.put("otherText", "2026-11-28T18:00:00Z").putNull("agreedBreakStartAt");
		var response = ClaimWorkResponseTime.gmt9(original);
		assertThat(response).isNotSameAs(original);
		assertThat(response.get("confirmedAt").stringValue()).isEqualTo("2026-11-29T03:00:00.123456+09:00");
		assertThat(original.get("confirmedAt").stringValue()).isEqualTo("2026-11-28T18:00:00.123456Z");
		assertThat(response.get("agreedWorkDate")).isEqualTo(original.get("agreedWorkDate"));
		assertThat(response.get("agreedTimeZone")).isEqualTo(original.get("agreedTimeZone"));
		assertThat(response.get("otherText")).isEqualTo(original.get("otherText"));
		assertThat(response.get("agreedBreakStartAt").isNull()).isTrue();
		assertThat(response.has("agreedBreakEndAt")).isFalse();
	}
	@Test void invalidStoredTimesFailWithoutKeepingTheOriginalValueInTheException() {
		for (String value : new String[]{"[]", "{\"confirmedAt\":\"private-invalid-time\"}", "{\"agreedStartAt\":123}"}) {
			assertThatThrownBy(() -> ClaimWorkResponseTime.gmt9(json.readTree(value)))
				.isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("private-invalid-time").hasNoCause();
		}
	}
}

package com.wonday.farm.change.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

// CHANGE-03 입력 검증·정규화. 순수 함수 단위 테스트이며 저장소·Spring 컨텍스트는 없다.
@DisplayName("CHANGE-03 조기 종료 입력 검증·설명 정규화")
class EarlyEndInputTest {
	private static final OffsetDateTime END = OffsetDateTime.parse("2026-12-02T14:00:00.123456+09:00");

	private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(e.code()).isEqualTo("INVALID_REQUEST");
		});
	}
	private static WorkEarlyEndRequest req(String reason, String weather, String detail) {
		return new WorkEarlyEndRequest(END, reason, weather, detail);
	}

	@Nested
	@DisplayName("경로 근무 ID")
	class Id {
		@ParameterizedTest
		@ValueSource(strings = {"1", "7001", "9223372036854775807"})
		void positive(String value) { assertThat(EarlyEndInput.id(value)).isEqualTo(Long.parseLong(value)); }

		@ParameterizedTest
		@ValueSource(strings = {"0", "-1", "+1", " 1", "1 ", "1.0", "1.5", "1e3", "1E3", "0x10", "01", "",
			"9223372036854775808", "99999999999999999999", "１", "a1"})
		void rejected(String value) { invalid(() -> EarlyEndInput.id(value)); }

		@ParameterizedTest
		@NullSource
		void nullRejected(String value) { invalid(() -> EarlyEndInput.id(value)); }
	}

	@Nested
	@DisplayName("실제 종료 시각·사유 조합")
	class Values {
		@Test
		void actualEndRequired() {
			invalid(() -> EarlyEndInput.request(new WorkEarlyEndRequest(null, "OTHER", null, null)));
			invalid(() -> EarlyEndInput.values(null, Reason.OTHER, null, null));
		}

		@Test
		void internalCallKeepsMicrosecondPrecision() {
			assertThat(EarlyEndInput.values(OffsetDateTime.parse("2026-12-02T14:00:00.123456+09:00"), Reason.OTHER, null, null)
				.actualEndAt().getNano()).isEqualTo(123_456_000);
			invalid(() -> EarlyEndInput.values(OffsetDateTime.parse("2026-12-02T14:00:00.1234567+09:00"), Reason.OTHER, null, null));
			invalid(() -> EarlyEndInput.values(OffsetDateTime.parse("2026-12-02T14:00:00.000000001Z"), Reason.OTHER, null, null));
		}

		@Test
		void actualEndOffsetIsPreservedAsGiven() {
			var input = EarlyEndInput.request(new WorkEarlyEndRequest(OffsetDateTime.parse("2026-12-02T05:00:00Z"), "OTHER", null, null));
			assertThat(input.actualEndAt()).isEqualTo(OffsetDateTime.parse("2026-12-02T05:00:00Z"));
			assertThat(input.actualEndAt().toInstant()).isEqualTo(OffsetDateTime.parse("2026-12-02T14:00+09:00").toInstant());
		}

		@ParameterizedTest
		@EnumSource(WeatherReason.class)
		void weatherWithEachDetail(WeatherReason weather) {
			var input = EarlyEndInput.request(req("WEATHER", weather.name(), null));
			assertThat(input.reason()).isEqualTo(Reason.WEATHER);
			assertThat(input.weather()).isEqualTo(weather);
		}

		@ParameterizedTest
		@ValueSource(strings = {"FARM_CIRCUMSTANCES", "OTHER"})
		void nonWeather(String reason) {
			assertThat(EarlyEndInput.request(req(reason, null, null)).weather()).isNull();
			for (var weather : WeatherReason.values()) invalid(() -> EarlyEndInput.request(req(reason, weather.name(), null)));
		}

		@Test
		void weatherRequiresDetailAndReasonRequired() {
			invalid(() -> EarlyEndInput.request(req("WEATHER", null, null)));
			invalid(() -> EarlyEndInput.request(req(null, null, null)));
			invalid(() -> EarlyEndInput.request(req(null, "RAIN", null)));
			invalid(() -> EarlyEndInput.request(null));
			invalid(() -> EarlyEndInput.values(END, null, null, null));
			invalid(() -> EarlyEndInput.values(END, Reason.WEATHER, null, null));
			invalid(() -> EarlyEndInput.values(END, Reason.OTHER, WeatherReason.WIND, null));
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "weather", "WEATHER ", "RAIN", "EARLY_ENDED", "0"})
		void invalidReasonCode(String code) {
			invalid(() -> EarlyEndInput.request(req(code, null, null)));
			invalid(() -> EarlyEndInput.request(req(code, "RAIN", null)));
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "rain", " RAIN", "SNOW", "WEATHER"})
		void invalidWeatherCode(String code) { invalid(() -> EarlyEndInput.request(req("WEATHER", code, null))); }
	}

	@Nested
	@DisplayName("설명 정규화·길이")
	class Detail {
		@Test
		void blankBecomesNull() {
			for (var blank : new String[] {null, "", " ", "\t\n", "\r\n ", "　", " ", "  "}) {
				assertThat(EarlyEndInput.request(req("OTHER", null, blank)).detail()).as("[%s]", blank).isNull();
			}
		}

		@Test
		void edgesTrimmedMiddleKept() {
			assertThat(EarlyEndInput.request(req("WEATHER", "RAIN", "  강수로 작업을 중단했습니다.  ")).detail()).isEqualTo("강수로 작업을 중단했습니다.");
			assertThat(EarlyEndInput.normalizeDetail("\n\t 첫 줄\n\n둘째  줄 　")).isEqualTo("첫 줄\n\n둘째  줄");
			assertThat(EarlyEndInput.normalizeDetail("   비 　 바람  ")).isEqualTo("비 　 바람");
		}

		@Test
		void fiveHundredCodePoints() {
			for (var unit : new String[] {"가", "a", "😀", "가a😀"}) {
				String exact = repeat(unit, 500), over = repeat(unit, 501);
				assertThat(EarlyEndInput.request(req("OTHER", null, " \n" + exact + "　")).detail()).as(unit).isEqualTo(exact);
				assertThatThrownBy(() -> EarlyEndInput.request(req("OTHER", null, over))).isInstanceOfSatisfying(WorkEarlyEndException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(e.getMessage()).contains("500자");
				});
			}
			assertThat("😀".repeat(500).length()).isEqualTo(1000);
			invalid(() -> EarlyEndInput.values(END, Reason.OTHER, null, "가" + " ".repeat(499) + "나"));
			assertThat(EarlyEndInput.values(END, Reason.OTHER, null, "가" + " ".repeat(498) + "나").detail()).hasSize(500);
		}

		private String repeat(String unit, int points) {
			var builder = new StringBuilder();
			int[] cps = unit.codePoints().toArray();
			for (int i = 0; i < points; i++) builder.appendCodePoint(cps[i % cps.length]);
			return builder.toString();
		}
	}
}

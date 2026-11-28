package com.wonday.farm.change.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.entity.WorkChange.Reason;
import com.wonday.farm.change.entity.WorkChange.WeatherReason;
import com.wonday.farm.change.exception.WorkCancellationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

// CHANGE-01·02 입력 검증·정규화. 순수 함수 단위 테스트이며 저장소·Spring 컨텍스트는 없다.
@DisplayName("CHANGE-01·02 취소 입력 검증·설명 정규화")
class CancellationInputTest {
	private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(WorkCancellationException.class, e -> {
			assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(e.code()).isEqualTo("INVALID_REQUEST");
		});
	}
	private static WorkCancellationRequest req(String reason, String weather, String detail) {
		return new WorkCancellationRequest(reason, weather, detail);
	}

	@Nested
	@DisplayName("경로 근무 ID")
	class Id {
		@ParameterizedTest
		@ValueSource(strings = {"1", "7", "31", "1000000", "9223372036854775807"})
		void acceptsPositiveDecimal(String value) {
			assertThat(CancellationInput.id(value)).isEqualTo(Long.parseLong(value));
		}

		@ParameterizedTest
		@ValueSource(strings = {"0", "-1", "+1", "1.0", "1.5", " 1", "1 ", "1e3", "1E3", "0x1F", "01", "",
			"9223372036854775808", "99999999999999999999", "１", "abc", "1/2"})
		void rejectsZeroNegativeSignedDecimalSpaceExponentOverflow(String value) {
			invalid(() -> CancellationInput.id(value));
		}

		@ParameterizedTest
		@NullSource
		void rejectsNull(String value) { invalid(() -> CancellationInput.id(value)); }
	}

	@Nested
	@DisplayName("사유 코드 조합")
	class Reasons {
		@ParameterizedTest
		@EnumSource(WeatherReason.class)
		void weatherRequiresEachDetailCode(WeatherReason weather) {
			var input = CancellationInput.reason(req("WEATHER", weather.name(), null));
			assertThat(input.reason()).isEqualTo(Reason.WEATHER);
			assertThat(input.weather()).isEqualTo(weather);
		}

		@ParameterizedTest
		@ValueSource(strings = {"FARM_CIRCUMSTANCES", "OTHER"})
		void nonWeatherWithoutWeatherDetail(String reason) {
			var input = CancellationInput.reason(req(reason, null, null));
			assertThat(input.reason()).isEqualTo(Reason.valueOf(reason));
			assertThat(input.weather()).isNull();
		}

		@Test
		void weatherWithoutDetailIsRejected() { invalid(() -> CancellationInput.reason(req("WEATHER", null, "비"))); }

		@ParameterizedTest
		@ValueSource(strings = {"FARM_CIRCUMSTANCES", "OTHER"})
		void nonWeatherWithWeatherDetailIsRejected(String reason) {
			for (var weather : WeatherReason.values()) invalid(() -> CancellationInput.reason(req(reason, weather.name(), null)));
		}

		@Test
		void reasonIsRequired() {
			invalid(() -> CancellationInput.reason(req(null, null, null)));
			invalid(() -> CancellationInput.reason(req(null, "RAIN", null)));
			invalid(() -> CancellationInput.reason((WorkCancellationRequest) null));
			invalid(() -> CancellationInput.reason(null, null, null));
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "weather", "Weather", " WEATHER", "WEATHER ", "RAIN", "CANCELLED", "SNOW", "0", "null"})
		void unknownOrEmptyReasonCodeIsRejected(String code) {
			invalid(() -> CancellationInput.reason(req(code, null, null)));
			invalid(() -> CancellationInput.reason(req(code, "RAIN", null)));
		}

		@ParameterizedTest
		@ValueSource(strings = {"", " ", "rain", "RAIN ", "SNOW", "WEATHER", "FARM_CIRCUMSTANCES"})
		void unknownOrEmptyWeatherCodeIsRejected(String code) {
			invalid(() -> CancellationInput.reason(req("WEATHER", code, null)));
		}
	}

	@Nested
	@DisplayName("상세 설명 정규화")
	class Detail {
		@Test
		void omittedNullBlankBecomeNull() {
			assertThat(CancellationInput.normalizeDetail(null)).isNull();
			for (var blank : new String[] {"", " ", "   ", "\t", "\n", "\r\n", " \t\n ", "　", " ", "  ", "  "}) {
				assertThat(CancellationInput.normalizeDetail(blank)).as("[%s]", blank).isNull();
				assertThat(CancellationInput.reason(req("OTHER", null, blank)).detail()).isNull();
			}
		}

		@Test
		void edgeWhitespaceIncludingUnicodeIsTrimmedMiddleKept() {
			assertThat(CancellationInput.normalizeDetail("  비가 옵니다  ")).isEqualTo("비가 옵니다");
			assertThat(CancellationInput.normalizeDetail("\t\n\r 첫 줄\n\n둘째  줄\t끝 \n\t")).isEqualTo("첫 줄\n\n둘째  줄\t끝");
			assertThat(CancellationInput.normalizeDetail("　       강풍 　 경보  　"))
				.isEqualTo("강풍 　 경보");
			assertThat(CancellationInput.normalizeDetail("\u001C\u001F x \u000B\f")).isEqualTo("x");
		}

		@Test
		void nonWhitespaceInvisibleCharactersAreNotTrimmed() {
			// U+200B(폭 없는 공백)는 Java 공백이 아니므로 정규화하지 않는다(현재 정책 그대로 기록).
			assertThat(CancellationInput.normalizeDetail("​비​")).isEqualTo("​비​");
		}

		@Test
		void fiveHundredCodePointsAcceptedFiveHundredOneRejectedAfterTrim() {
			for (var unit : new String[] {"가", "a", "😀", "가a😀"}) {
				String exact = repeatPoints(unit, 500), over = repeatPoints(unit, 501);
				assertThat(exact.codePointCount(0, exact.length())).isEqualTo(500);
				var accepted = CancellationInput.reason(req("OTHER", null, " \n\t" + exact + "　 \n"));
				assertThat(accepted.detail()).as(unit).isEqualTo(exact);
				assertThatThrownBy(() -> CancellationInput.reason(req("OTHER", null, over)))
					.isInstanceOfSatisfying(WorkCancellationException.class, e -> {
						assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
						assertThat(e.code()).isEqualTo("INVALID_REQUEST");
						assertThat(e.getMessage()).contains("500자").doesNotContain(over.substring(0, 2));
					});
			}
		}

		@Test
		void emojiCountsAsOneCodePointNotTwoUtf16Units() {
			String emoji = "😀".repeat(500);
			assertThat(emoji.length()).isEqualTo(1000);
			assertThat(CancellationInput.reason(req("WEATHER", "RAIN", emoji)).detail()).isEqualTo(emoji);
			invalid(() -> CancellationInput.reason(req("WEATHER", "RAIN", emoji + "😀")));
			// 499개 + 한 글자 = 500 코드 포인트(UTF-16 999)
			String mixed = "😀".repeat(499) + "a";
			assertThat(CancellationInput.reason(req("OTHER", null, mixed)).detail()).isEqualTo(mixed);
		}

		@Test
		void tooLongIsNotTruncated() {
			String over = "가".repeat(501);
			invalid(() -> CancellationInput.reason(Reason.OTHER, null, over));
			// 내부 오버로드도 같은 검사를 적용하고, 잘라서 반환하는 경로가 없다.
			invalid(() -> CancellationInput.reason(Reason.WEATHER, WeatherReason.HEAT, "x".repeat(10_000)));
		}

		@Test
		void middleSpacesCountTowardLimit() {
			String inner = "가" + " ".repeat(498) + "나";
			assertThat(CancellationInput.reason(req("OTHER", null, inner)).detail()).isEqualTo(inner);
			invalid(() -> CancellationInput.reason(req("OTHER", null, "가" + " ".repeat(499) + "나")));
		}

		private String repeatPoints(String unit, int points) {
			var builder = new StringBuilder();
			int[] cps = unit.codePoints().toArray();
			for (int i = 0; i < points; i++) builder.appendCodePoint(cps[i % cps.length]);
			return builder.toString();
		}
	}
}

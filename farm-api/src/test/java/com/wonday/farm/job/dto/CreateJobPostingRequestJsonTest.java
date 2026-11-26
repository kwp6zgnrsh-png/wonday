package com.wonday.farm.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 요청 레코드의 필드별 역직렬화 설정(StrictInteger/LongDeserializer, @JsonFormat(lenient = FALSE))을
// Spring 없이 Jackson으로 직접 확인한다. 필드 단위 설정이라 전역 Jackson 설정과 무관하게 동작해야 한다.
// 실제 HTTP에서 이 예외가 400 INVALID_REQUEST_BODY로 바뀌는 경로는 통합 테스트(미실행)가 맡는다.
class CreateJobPostingRequestJsonTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String BODY = """
		{"title":"t","description":"d","cropCode":"C","workTypeCode":"W","workLocation":"L","timeZone":"GMT+9",
		 "latitude":37.123456,"longitude":127.123456,"headcount":%s,"dailyWage":%s,
		 "schedules":[{"workDate":%s,"plannedStartAt":%s,"plannedEndAt":%s,"breakStartAt":%s,"breakEndAt":%s}]}""";

	@Nested
	class Numbers {
		@Test
		void plainJsonIntegersAreRead() {
			var request = read(body("2", "120000"));

			assertThat(request.headcount()).isEqualTo(2);
			assertThat(request.dailyWage()).isEqualTo(120000L);
		}

		@Test
		void integerAndLongLimitsAreRead() {
			var request = read(body(String.valueOf(Integer.MAX_VALUE), String.valueOf(Long.MAX_VALUE)));

			assertThat(request.headcount()).isEqualTo(Integer.MAX_VALUE);
			assertThat(request.dailyWage()).isEqualTo(Long.MAX_VALUE);
		}

		@Test
		void nullZeroAndNegativeAreReadAndLeftToBeanValidation() {
			// null·0·음수는 형식상 정수이므로 역직렬화는 통과하고, @NotNull·@Positive가 거절한다.
			assertThat(read(body("null", "null")).headcount()).isNull();
			assertThat(read(body("0", "0")).dailyWage()).isZero();
			assertThat(read(body("-1", "-120000")).headcount()).isEqualTo(-1);
			assertThat(read(body("-0", "1")).headcount()).isZero();
		}

		@ParameterizedTest(name = "headcount={0}")
		@ValueSource(strings = {"1.5", "2.0", "\"2\"", "\"two\"", "1e1", "2E0", "true", "false", "[2]", "{}", "{\"v\":2}",
			"2147483648", "-2147483649", "\"\""})
		void headcountRejectsAnythingButIntInRange(String value) {
			assertRejected(body(value, "120000"));
		}

		@ParameterizedTest(name = "dailyWage={0}")
		@ValueSource(strings = {"120000.9", "120000.0", "\"120000\"", "1.2e5", "1E5", "true", "[120000]", "{}",
			"9223372036854775808", "-9223372036854775809", "\"\""})
		void dailyWageRejectsAnythingButLongInRange(String value) {
			assertRejected(body("2", value));
		}

		private static String body(String headcount, String dailyWage) {
			return BODY.formatted(headcount, dailyWage, q("2026-11-30"), q("2026-11-30T08:00:00"),
				q("2026-11-30T16:00:00"), "null", "null");
		}
	}

	@Nested
	class LocalDateTimes {
		@Test
		void localDateTimesWithMicrosecondsAreReadExactly() {
			var schedule = read(schedule(q("2026-11-30"), q("2026-11-30T08:00:00.123456"), q("2026-11-30T16:00:00.999999"),
				q("2026-11-30T12:00:00.000001"), q("2026-11-30T13:00:00"))).schedules().getFirst();

			assertThat(schedule.workDate()).isEqualTo(LocalDate.of(2026, 11, 30));
			assertThat(schedule.plannedStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 8, 0, 0, 123_456_000));
			assertThat(schedule.plannedEndAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 16, 0, 0, 999_999_000));
			assertThat(schedule.breakStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 12, 0, 0, 1_000));
			assertThat(schedule.breakEndAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 13, 0));
		}

		@Test
		void nullBreaksAreRead() {
			var schedule = read(schedule(q("2026-11-30"), q("2026-11-30T08:00:00"), q("2026-11-30T16:00:00"), "null", "null"))
				.schedules().getFirst();

			assertThat(schedule.breakStartAt()).isNull();
			assertThat(schedule.breakEndAt()).isNull();
		}

		@ParameterizedTest(name = "plannedStartAt={0}")
		@ValueSource(strings = {"2026-11-30T08:00:00Z", "2026-11-30T08:00:00z", "2026-11-30T08:00:00+09:00",
			"2026-11-30T08:00:00-05:00", "2026-11-30T08:00:00+0900", "2026-11-30T08:00:00.123456Z",
			"2026-11-30T08:00:00+09:00[Asia/Seoul]", "2026-11-30T08:00:00[Asia/Seoul]"})
		void plannedStartWithZoneDesignatorIsRejected(String value) {
			assertRejected(schedule(q("2026-11-30"), q(value), q("2026-11-30T16:00:00"), "null", "null"));
		}

		@ParameterizedTest(name = "plannedEndAt={0}")
		@ValueSource(strings = {"2026-11-30T16:00:00Z", "2026-11-30T16:00:00+09:00"})
		void plannedEndWithZoneDesignatorIsRejected(String value) {
			assertRejected(schedule(q("2026-11-30"), q("2026-11-30T08:00:00"), q(value), "null", "null"));
		}

		@ParameterizedTest(name = "breakStartAt={0}")
		@ValueSource(strings = {"2026-11-30T12:00:00Z", "2026-11-30T12:00:00+09:00"})
		void breakStartWithZoneDesignatorIsRejected(String value) {
			assertRejected(schedule(q("2026-11-30"), q("2026-11-30T08:00:00"), q("2026-11-30T16:00:00"), q(value),
				q("2026-11-30T13:00:00")));
		}

		@ParameterizedTest(name = "breakEndAt={0}")
		@ValueSource(strings = {"2026-11-30T13:00:00Z", "2026-11-30T13:00:00+09:00"})
		void breakEndWithZoneDesignatorIsRejected(String value) {
			assertRejected(schedule(q("2026-11-30"), q("2026-11-30T08:00:00"), q("2026-11-30T16:00:00"),
				q("2026-11-30T12:00:00"), q(value)));
		}

		@ParameterizedTest(name = "workDate={0}")
		@ValueSource(strings = {"\"2026-02-30\"", "\"2026/11/30\"", "\"2026-11-30Z\"", "\"2026-11-30+09:00\"",
			"\"2026-11-30T00:00:00\"", "\"\"", "20787"})
		void malformedWorkDateIsRejected(String value) {
			assertRejected(schedule(value, q("2026-11-30T08:00:00"), q("2026-11-30T16:00:00"), "null", "null"));
		}

		@ParameterizedTest(name = "plannedStartAt={0}")
		@ValueSource(strings = {"\"2026-11-30 08:00:00\"", "\"2026-11-30\"", "\"2026-11-31T08:00:00\"", "\"2026-11-30T24:00:00\"",
			"\"\"", "\"1764489600000\"", "1764489600000"})
		void malformedLocalDateTimeIsRejected(String value) {
			assertRejected(schedule(q("2026-11-30"), value, q("2026-11-30T16:00:00"), "null", "null"));
		}

		private static String schedule(String workDate, String start, String end, String breakStart, String breakEnd) {
			return BODY.formatted("2", "120000", workDate, start, end, breakStart, breakEnd);
		}
	}

	private static CreateJobPostingRequest read(String json) {
		return JSON.readValue(json, CreateJobPostingRequest.class);
	}

	private static void assertRejected(String json) {
		assertThatThrownBy(() -> read(json)).isInstanceOf(JacksonException.class);
	}

	private static String q(String value) {
		return "\"" + value + "\"";
	}
}

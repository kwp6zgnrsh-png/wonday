package com.wonday.farm.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 수정 요청 DTO에 붙은 운영 Strict 역직렬화기와 Bean Validation을 Spring 없이 실제로 실행한다.
// HTTP에서 이 예외가 400 INVALID_REQUEST_BODY/INVALID_WORK_SCHEDULE로 바뀌는 경로는 WorkScheduleUpdateApiTest(standalone MockMvc)에서 본다.
class UpdateWorkScheduleRequestTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();
	private static final String[] DATE_TIME_FIELDS = {"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt"};

	@AfterAll
	static void close() {
		FACTORY.close();
	}

	@Nested
	class ValidBody {
		@Test
		void readsTheDocumentedExample() {
			var request = read(body("1", "\"2026-12-02\"", "\"2026-12-02T09:00:00\"", "\"2026-12-02T17:00:00\"",
				"\"2026-12-02T12:00:00\"", "\"2026-12-02T13:00:00\""));

			assertThat(request).isEqualTo(new UpdateWorkScheduleRequest(1, LocalDate.parse("2026-12-02"),
				LocalDateTime.parse("2026-12-02T09:00"), LocalDateTime.parse("2026-12-02T17:00"),
				LocalDateTime.parse("2026-12-02T12:00"), LocalDateTime.parse("2026-12-02T13:00")));
			assertThat(VALIDATOR.validate(request)).isEmpty();
		}

		@Test
		void breakFieldsAreOptional() {
			var request = read("{\"expectedTermsVersion\":1,\"workDate\":\"2026-12-02\",\"plannedStartAt\":\"2026-12-02T09:00\","
				+ "\"plannedEndAt\":\"2026-12-02T17:00\",\"breakStartAt\":null}");

			assertThat(request.breakStartAt()).isNull();
			assertThat(request.breakEndAt()).isNull();
			assertThat(VALIDATOR.validate(request)).isEmpty();
		}

		@Test
		void keepsMicrosecondsAndAcceptsTimeWithoutSeconds() {
			var request = read(body("1", "\"2026-12-02\"", "\"2026-12-02T09:00\"", "\"2026-12-02T17:00:00.123456\"", "null", "null"));

			assertThat(request.plannedStartAt()).isEqualTo(LocalDateTime.parse("2026-12-02T09:00"));
			assertThat(request.plannedEndAt()).isEqualTo(LocalDateTime.parse("2026-12-02T17:00:00.123456"));
		}

		@Test
		void maxIntVersionIsRead() {
			assertThat(read(body(String.valueOf(Integer.MAX_VALUE), "\"2026-12-02\"", "\"2026-12-02T09:00\"",
				"\"2026-12-02T17:00\"", "null", "null")).expectedTermsVersion()).isEqualTo(Integer.MAX_VALUE);
		}
	}

	@Nested
	class ExpectedTermsVersion {
		@ParameterizedTest(name = "expectedTermsVersion={0}")
		@ValueSource(strings = {"1.5", "1.0", "1e0", "\"1\"", "\"\"", "[1]", "{}", "true", "false", "2147483648", "-2147483649"})
		void rejectsAnythingButAnIntInRange(String value) {
			assertRejected(body(value, "\"2026-12-02\"", "\"2026-12-02T09:00\"", "\"2026-12-02T17:00\"", "null", "null"));
		}

		@ParameterizedTest(name = "expectedTermsVersion={0}")
		@ValueSource(strings = {"0", "-1", "null"})
		void zeroNegativeOrNullIsABeanValidationViolation(String value) {
			var request = read(body(value, "\"2026-12-02\"", "\"2026-12-02T09:00\"", "\"2026-12-02T17:00\"", "null", "null"));

			assertThat(violations(request)).containsExactly("expectedTermsVersion");
		}
	}

	@Nested
	class RequiredFields {
		@Test
		void workDateAndPlannedTimesAreRequired() {
			var request = read("{\"expectedTermsVersion\":1}");

			assertThat(violations(request)).containsExactlyInAnyOrder("workDate", "plannedStartAt", "plannedEndAt");
		}

		@Test
		void explicitNullsAreAlsoViolations() {
			var request = read(body("1", "null", "null", "null", "null", "null"));

			assertThat(violations(request)).containsExactlyInAnyOrder("workDate", "plannedStartAt", "plannedEndAt");
		}
	}

	@Nested
	class DateFormat {
		@ParameterizedTest(name = "workDate={0}")
		@ValueSource(strings = {"[2026,12,2]", "20261202", "true", "{}", "\"2026-02-30\"", "\"2026-13-01\"", "\"2026/12/02\"",
			"\"2026-12-2\"", "\" 2026-12-02\"", "\"2026-12-02T00:00\"", "\"\""})
		void workDateAcceptsOnlyAValidIsoDateString(String value) {
			assertRejected(body("1", value, "\"2026-12-02T09:00\"", "\"2026-12-02T17:00\"", "null", "null"));
		}
	}

	@Nested
	class DateTimeFormat {
		@ParameterizedTest(name = "value={0}")
		@ValueSource(strings = {"[2026,12,2,9,0]", "1764666000", "true", "{}", "\"2026-12-02t09:00\"", "\"2026-12-02 09:00\"",
			"\"2026-12-02T09:00Z\"", "\"2026-12-02T09:00:00+09:00\"", "\"2026-12-02T09:00:00.000+0900\"",
			"\"2026-12-02T09:00[Asia/Seoul]\"", "\"2026-12-02T24:00\"", "\"2026-12-02T09:60\"", "\"2026-02-30T09:00\"",
			"\"2026-12-02\"", "\"2026-12-02T\"", "\"\""})
		void everyDateTimeFieldRejectsTheSameInvalidNotation(String value) {
			for (String field : DATE_TIME_FIELDS) {
				assertRejected(bodyWith(field, value));
			}
		}
	}

	private static String body(String version, String workDate, String start, String end, String breakStart, String breakEnd) {
		return "{\"expectedTermsVersion\":" + version + ",\"workDate\":" + workDate + ",\"plannedStartAt\":" + start
			+ ",\"plannedEndAt\":" + end + ",\"breakStartAt\":" + breakStart + ",\"breakEndAt\":" + breakEnd + "}";
	}

	private static String bodyWith(String field, String value) {
		String[] values = {"\"2026-12-02T09:00\"", "\"2026-12-02T17:00\"", "\"2026-12-02T12:00\"", "\"2026-12-02T13:00\""};
		for (int i = 0; i < DATE_TIME_FIELDS.length; i++) {
			if (DATE_TIME_FIELDS[i].equals(field)) values[i] = value;
		}
		return body("1", "\"2026-12-02\"", values[0], values[1], values[2], values[3]);
	}

	private static UpdateWorkScheduleRequest read(String json) {
		return JSON.readValue(json, UpdateWorkScheduleRequest.class);
	}

	private static void assertRejected(String json) {
		assertThatThrownBy(() -> read(json)).as(json).isInstanceOf(JacksonException.class);
	}

	private static Set<String> violations(UpdateWorkScheduleRequest request) {
		return VALIDATOR.validate(request).stream().map(violation -> violation.getPropertyPath().toString())
			.collect(Collectors.toSet());
	}
}

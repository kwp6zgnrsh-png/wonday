package com.wonday.farm.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 날짜·일시의 표기 형식 검증 (StrictLocalDate/LocalDateTimeDeserializer).
// DEFECT-5(배열 형식)·DEFECT-6(소문자 t)의 재현 테스트였다. 2026-11-24 재수정으로 통과해 known-defect 태그를 지웠다.
class CreateJobPostingRequestJsonNotationTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String WORK_DATE = "\"2026-11-30\"";
	private static final String START = "\"2026-11-30T08:00:00\"";
	private static final String END = "\"2026-11-30T16:00:00\"";
	private static final String BREAK_START = "\"2026-11-30T12:00:00\"";
	private static final String BREAK_END = "\"2026-11-30T13:00:00\"";

	@Nested
	@DisplayName("DEFECT-5 날짜·일시의 배열 형식은 거절한다")
	class ArrayNotation {
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"workDate", "plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt"})
		void arrayNotationIsRejected(String field) {
			String array = switch (field) {
				case "workDate" -> "[2026,11,30]";
				case "plannedStartAt" -> "[2026,11,30,8,0]";
				case "plannedEndAt" -> "[2026,11,30,16,0]";
				case "breakStartAt" -> "[2026,11,30,12,0]";
				default -> "[2026,11,30,13,0]";
			};
			assertRejected(field, array);
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"workDate", "plannedStartAt"})
		void numberAndObjectAreRejected(String field) {
			assertRejected(field, "20261130");
			assertRejected(field, "{\"year\":2026}");
			assertRejected(field, "true");
		}
	}

	@Nested
	@DisplayName("DEFECT-6 일시 구분자는 대문자 T만 받는다")
	class Separator {
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"plannedStartAt", "plannedEndAt", "breakStartAt", "breakEndAt"})
		void lowercaseTSeparatorIsRejected(String field) {
			assertRejected(field, valueOf(field).replace('T', 't'));
		}

		@ParameterizedTest(name = "plannedStartAt={0}")
		@ValueSource(strings = {"\"2026-11-30 08:00:00\"", "\"2026-11-30_08:00:00\"", "\" 2026-11-30T08:00:00\"",
			"\"2026-11-30T08:00:00 \"", "\"2026-11-30T\"", "\"2026-11-30\"", "\"+10000-01-01T00:00:00\""})
		void otherSeparatorsAndPaddingAreRejected(String value) {
			assertRejected("plannedStartAt", value);
		}

		@ParameterizedTest(name = "workDate={0}")
		@ValueSource(strings = {"\" 2026-11-30\"", "\"2026-11-30 \"", "\"20261130\""})
		void paddedOrCompactWorkDateIsRejected(String value) {
			assertRejected("workDate", value);
		}
	}

	@Nested
	@DisplayName("표준 ISO 현지 일시는 그대로 받는다")
	class Accepted {
		@Test
		void uppercaseTWithAndWithoutSecondsIsAccepted() {
			// 초 생략(08:00)은 ISO 표준 형식이라 허용한다 (검증 요청자 확인, 2026-11-24).
			var schedule = read(json("plannedStartAt", "\"2026-11-30T08:00\"")).schedules().getFirst();

			assertThat(schedule.workDate()).isEqualTo(LocalDate.of(2026, 11, 30));
			assertThat(schedule.plannedStartAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 8, 0));
			assertThat(schedule.plannedEndAt()).isEqualTo(LocalDateTime.of(2026, 11, 30, 16, 0));
		}

		@Test
		void yearBelowDbRangeIsReadAndLeftToServiceValidation() {
			// 0999년은 형식상 올바른 ISO 일시이므로 읽고, DB 범위 검사는 서비스가 INVALID_LOCAL_TIME으로 맡는다.
			var schedule = read(json("plannedStartAt", "\"0999-12-31T08:00:00\"")).schedules().getFirst();

			assertThat(schedule.plannedStartAt().getYear()).isEqualTo(999);
		}
	}

	private static String valueOf(String field) {
		return switch (field) {
			case "plannedStartAt" -> START;
			case "plannedEndAt" -> END;
			case "breakStartAt" -> BREAK_START;
			default -> BREAK_END;
		};
	}

	private static CreateJobPostingRequest read(String json) {
		return JSON.readValue(json, CreateJobPostingRequest.class);
	}

	private static void assertRejected(String field, String value) {
		assertThatThrownBy(() -> read(json(field, value)))
			.as("%s=%s", field, value)
			.isInstanceOf(JacksonException.class);
	}

	private static String json(String field, String value) {
		return """
			{"title":"t","description":"d","cropCode":"C","workTypeCode":"W","workLocation":"L","timeZone":"GMT+9",
			 "headcount":2,"dailyWage":120000,
			 "schedules":[{"workDate":%s,"plannedStartAt":%s,"plannedEndAt":%s,"breakStartAt":%s,"breakEndAt":%s}]}"""
			.formatted(
				field.equals("workDate") ? value : WORK_DATE,
				field.equals("plannedStartAt") ? value : START,
				field.equals("plannedEndAt") ? value : END,
				field.equals("breakStartAt") ? value : BREAK_START,
				field.equals("breakEndAt") ? value : BREAK_END);
	}
}

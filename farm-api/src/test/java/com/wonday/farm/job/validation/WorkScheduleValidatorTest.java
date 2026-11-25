package com.wonday.farm.job.validation;

import static com.wonday.farm.job.testsupport.JobPostingFixtures.schedule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.exception.JobPostingException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 등록(JobPostingService)과 수정(WorkScheduleUpdateService)이 공유하는 일정 검증기.
// 같은 규칙이 접두어("schedules[0]" 또는 "")에 따라 배열 경로 또는 단일 필드명을 반환하는지 확인한다.
class WorkScheduleValidatorTest {
	private static final ZoneId GMT9 = ZoneId.of("GMT+9");

	@Nested
	class FieldNames {
		@ParameterizedTest(name = "{0} → {2}")
		@CsvSource(delimiter = '|', value = {
			"workDate mismatch      | 2026-12-03,2026-12-02T09:00,2026-12-02T18:00,,                                   | workDate",
			"end not after start    | 2026-12-02,2026-12-02T09:00,2026-12-02T09:00,,                                   | plannedEndAt",
			"break start missing    | 2026-12-02,2026-12-02T09:00,2026-12-02T18:00,,2026-12-02T13:00                   | breakStartAt",
			"break end missing      | 2026-12-02,2026-12-02T09:00,2026-12-02T18:00,2026-12-02T12:00,                   | breakEndAt",
			"break end before start | 2026-12-02,2026-12-02T09:00,2026-12-02T18:00,2026-12-02T13:00,2026-12-02T12:00   | breakEndAt",
			"break before work      | 2026-12-02,2026-12-02T09:00,2026-12-02T18:00,2026-12-02T08:00,2026-12-02T10:00   | breakStartAt",
			"break after work       | 2026-12-02,2026-12-02T09:00,2026-12-02T18:00,2026-12-02T17:00,2026-12-02T19:00   | breakEndAt",
			"sub-micro start        | 2026-12-02,2026-12-02T09:00:00.0000001,2026-12-02T18:00,,                        | plannedStartAt"})
		void registrationUsesArrayPathAndUpdateUsesBareField(String label, String values, String field) {
			var conditions = parse(values);

			assertField(conditions, "schedules[0]", "schedules[0]." + field);
			assertField(conditions, "schedules[3]", "schedules[3]." + field);
			assertField(conditions, "", field);
		}

		private void assertField(CreateJobPostingRequest.Schedule conditions, String prefix, String expected) {
			assertThatThrownBy(() -> WorkScheduleValidator.validate(conditions, GMT9, prefix))
				.isInstanceOfSatisfying(JobPostingException.class, error -> assertThat(error.field()).isEqualTo(expected));
		}
	}

	@Nested
	class Rules {
		@Test
		void validScheduleWithAndWithoutBreakPasses() {
			assertThatCode(() -> WorkScheduleValidator.validate(
				schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", null, null), GMT9, "")).doesNotThrowAnyException();
			assertThatCode(() -> WorkScheduleValidator.validate(
				schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T09:00", "2026-12-02T18:00"), GMT9, ""))
				.doesNotThrowAnyException();
		}

		@Test
		void codesDistinguishWorkBreakAndLocalTimeErrors() {
			assertCode(schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T08:00", null, null), "INVALID_WORK_TIME");
			assertCode(schedule("2026-12-02", "2026-12-02T09:00", "2026-12-02T18:00", "2026-12-02T12:00", null),
				"INVALID_BREAK_TIME");
			assertCode(schedule("0999-12-31", "0999-12-31T09:00", "0999-12-31T18:00", null, null), "INVALID_LOCAL_TIME");
		}

		@Test
		void localTimeThatDoesNotExistInAStoredDstZoneIsInvalid() {
			// 수정은 기존 공고의 저장 시간대를 그대로 쓰므로, 서머타임 지역 시간대가 저장돼 있으면 이 규칙이 적용된다.
			var gap = schedule("2026-03-08", "2026-03-08T02:30", "2026-03-08T05:00", null, null);

			assertThatThrownBy(() -> WorkScheduleValidator.validate(gap, ZoneId.of("America/New_York"), ""))
				.isInstanceOfSatisfying(JobPostingException.class, error -> {
					assertThat(error.code()).isEqualTo("INVALID_LOCAL_TIME");
					assertThat(error.field()).isEqualTo("plannedStartAt");
				});
		}

		private void assertCode(CreateJobPostingRequest.Schedule conditions, String code) {
			assertThatThrownBy(() -> WorkScheduleValidator.validate(conditions, GMT9, ""))
				.isInstanceOfSatisfying(JobPostingException.class, error -> assertThat(error.code()).isEqualTo(code));
		}
	}

	private static CreateJobPostingRequest.Schedule parse(String values) {
		String[] parts = values.split(",", -1);
		return schedule(parts[0].strip(), parts[1].strip(), parts[2].strip(), blankToNull(parts[3]), blankToNull(parts[4]));
	}

	private static String blankToNull(String value) {
		return value.isBlank() ? null : value.strip();
	}
}

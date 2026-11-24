package com.wonday.farm.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// 요청 레코드의 Bean Validation 제약(필수·길이·양수·일정 목록)을 항목별로 확인한다.
class CreateJobPostingRequestValidationTest {
	private static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Test
	void validRequestHasNoViolations() {
		assertThat(VALIDATOR.validate(valid())).isEmpty();
	}

	@Test
	void valuesExactlyAtLengthLimitsAreValid() {
		var request = new Builder().title("가".repeat(200)).cropCode("C".repeat(40)).workTypeCode("W".repeat(40))
			.workLocation("나".repeat(500)).timeZone("A".repeat(64)).build();

		assertThat(VALIDATOR.validate(request)).isEmpty();
	}

	static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of("title null", edit(b -> b.title(null)), "title"),
			Arguments.of("title 빈 값", edit(b -> b.title("")), "title"),
			Arguments.of("title 공백", edit(b -> b.title(" \t\n")), "title"),
			Arguments.of("title 201자", edit(b -> b.title("가".repeat(201))), "title"),
			Arguments.of("description null", edit(b -> b.description(null)), "description"),
			Arguments.of("description 공백", edit(b -> b.description("   ")), "description"),
			Arguments.of("cropCode null", edit(b -> b.cropCode(null)), "cropCode"),
			Arguments.of("cropCode 공백", edit(b -> b.cropCode(" ")), "cropCode"),
			Arguments.of("cropCode 41자", edit(b -> b.cropCode("C".repeat(41))), "cropCode"),
			Arguments.of("workTypeCode 빈 값", edit(b -> b.workTypeCode("")), "workTypeCode"),
			Arguments.of("workTypeCode 41자", edit(b -> b.workTypeCode("W".repeat(41))), "workTypeCode"),
			Arguments.of("workLocation null", edit(b -> b.workLocation(null)), "workLocation"),
			Arguments.of("workLocation 501자", edit(b -> b.workLocation("나".repeat(501))), "workLocation"),
			Arguments.of("timeZone null", edit(b -> b.timeZone(null)), "timeZone"),
			Arguments.of("timeZone 공백", edit(b -> b.timeZone("  ")), "timeZone"),
			Arguments.of("timeZone 65자", edit(b -> b.timeZone("A".repeat(65))), "timeZone"),
			Arguments.of("headcount null", edit(b -> b.headcount(null)), "headcount"),
			Arguments.of("headcount 0", edit(b -> b.headcount(0)), "headcount"),
			Arguments.of("headcount 음수", edit(b -> b.headcount(-3)), "headcount"),
			Arguments.of("dailyWage null", edit(b -> b.dailyWage(null)), "dailyWage"),
			Arguments.of("dailyWage 0", edit(b -> b.dailyWage(0L)), "dailyWage"),
			Arguments.of("dailyWage 음수", edit(b -> b.dailyWage(-1L)), "dailyWage"),
			Arguments.of("schedules null", edit(b -> b.schedules(null)), "schedules"),
			Arguments.of("schedules 빈 배열", edit(b -> b.schedules(List.of())), "schedules"),
			Arguments.of("schedules null 원소", edit(b -> b.schedules(new ArrayList<>(Arrays.asList(schedule(), null)))), "schedules[1]"),
			Arguments.of("workDate null", edit(b -> b.schedules(List.of(new CreateJobPostingRequest.Schedule(
				null, start(), end(), null, null)))), "schedules[0].workDate"),
			Arguments.of("plannedStartAt null", edit(b -> b.schedules(List.of(new CreateJobPostingRequest.Schedule(
				date(), null, end(), null, null)))), "schedules[0].plannedStartAt"),
			Arguments.of("plannedEndAt null", edit(b -> b.schedules(List.of(new CreateJobPostingRequest.Schedule(
				date(), start(), null, null, null)))), "schedules[0].plannedEndAt"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void invalidValueIsReportedOnItsField(String name, CreateJobPostingRequest request, String field) {
		var violations = VALIDATOR.validate(request);

		// 컨테이너 원소 제약의 경로는 "schedules[1].<list element>"로 나온다. API 응답의 field("schedules[1]")는 HTTP 테스트에서 확인한다.
		assertThat(violations).singleElement()
			.extracting(v -> v.getPropertyPath().toString().replace(".<list element>", ""))
			.isEqualTo(field);
	}

	@Test
	void breakTimesAreOptionalForBeanValidation() {
		// 휴게시간은 둘 다 없거나 둘 다 있어야 한다는 규칙은 서비스 검증이 맡는다.
		var request = edit(b -> b.schedules(List.of(new CreateJobPostingRequest.Schedule(date(), start(), end(), null, end()))));

		assertThat(VALIDATOR.validate(request)).isEmpty();
	}

	@Test
	void everyViolationOfMultipleFieldsIsCollected() {
		var request = edit(b -> b.title("").headcount(0));

		assertThat(VALIDATOR.validate(request)).extracting(ConstraintViolation::getPropertyPath)
			.extracting(Object::toString).containsExactlyInAnyOrder("title", "headcount");
	}

	private static CreateJobPostingRequest valid() {
		return new Builder().build();
	}

	private static CreateJobPostingRequest edit(UnaryOperator<Builder> change) {
		return change.apply(new Builder()).build();
	}

	private static CreateJobPostingRequest.Schedule schedule() {
		return new CreateJobPostingRequest.Schedule(date(), start(), end(), null, null);
	}

	private static LocalDate date() {
		return LocalDate.parse("2026-11-30");
	}

	private static LocalDateTime start() {
		return LocalDateTime.parse("2026-11-30T08:00");
	}

	private static LocalDateTime end() {
		return LocalDateTime.parse("2026-11-30T16:00");
	}

	private static final class Builder {
		private String title = "사과 수확 근로자 모집";
		private String description = "사과 수확과 운반 작업입니다.";
		private String cropCode = JobPostingRequests.CROP_CODE;
		private String workTypeCode = JobPostingRequests.WORK_TYPE_CODE;
		private String workLocation = "충청북도 충주시 예시농장";
		private String timeZone = "GMT+9";
		private Integer headcount = 2;
		private Long dailyWage = 120000L;
		private List<CreateJobPostingRequest.Schedule> schedules = List.of(schedule());

		Builder title(String value) { title = value; return this; }
		Builder description(String value) { description = value; return this; }
		Builder cropCode(String value) { cropCode = value; return this; }
		Builder workTypeCode(String value) { workTypeCode = value; return this; }
		Builder workLocation(String value) { workLocation = value; return this; }
		Builder timeZone(String value) { timeZone = value; return this; }
		Builder headcount(Integer value) { headcount = value; return this; }
		Builder dailyWage(Long value) { dailyWage = value; return this; }
		Builder schedules(List<CreateJobPostingRequest.Schedule> value) { schedules = value; return this; }

		CreateJobPostingRequest build() {
			return new CreateJobPostingRequest(title, description, cropCode, workTypeCode, workLocation, timeZone,
				headcount, dailyWage, schedules);
		}
	}
}

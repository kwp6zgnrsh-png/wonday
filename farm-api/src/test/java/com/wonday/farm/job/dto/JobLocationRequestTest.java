package com.wonday.farm.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 실제 CreateJobPostingRequest를 Jackson으로 읽는 단계(StrictCoordinateDeserializer)와 Bean Validation 단계를 분리해 확인한다.
// 제목·일정 등 나머지 필수값은 모두 정상이고 좌표 원문만 바꾼다. 역직렬화 성공은 null·범위 통과를 뜻하지 않는다.
@DisplayName("공고 등록 요청의 좌표 형식·필수값 (Jackson + Bean Validation)")
class JobLocationRequestTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();
	private static final String TEMPLATE = """
		{"title":"사과 수확 근로자 모집","description":"사과 수확과 운반 작업입니다.","cropCode":"TEST-CROP",
		 "workTypeCode":"TEST-WORK","workLocation":"충청북도 충주시 예시농장",%s"timeZone":"GMT+9",
		 "headcount":2,"dailyWage":120000,
		 "schedules":[{"workDate":"2026-11-30","plannedStartAt":"2026-11-30T08:00:00",
		   "plannedEndAt":"2026-11-30T16:00:00","breakStartAt":null,"breakEndAt":null}]}""";

	@AfterAll
	static void close() {
		FACTORY.close();
	}

	@Nested
	@DisplayName("역직렬화: JSON 숫자만 받고 원본 BigDecimal을 유지")
	class Reading {
		@ParameterizedTest(name = "latitude={0}")
		@CsvSource({"37, 37", "37.12345678, 37.12345678", "0, 0", "-0, 0", "0.0, 0.0", "-90, -90", "90, 90",
			"90.0000000000000001, 90.0000000000000001", "37.1234565, 37.1234565", "3.7e1, 3.7E+1", "9E1, 9E+1",
			"9.00000004E1, 9.00000004E+1", "1e-7, 1E-7"})
		@DisplayName("정수·소수·지수 표기 JSON 숫자는 원문 그대로 BigDecimal로 읽고 반올림하지 않는다")
		void numbersKeepOriginal(String raw, String expected) {
			var request = read(raw, "127.12345678");

			assertThat(request.latitude()).isEqualTo(new BigDecimal(expected));
			assertThat(request.latitude().toString()).isEqualTo(new BigDecimal(expected).toString());
			assertThat(request.longitude()).isEqualTo(new BigDecimal("127.12345678"));
		}

		@Test
		@DisplayName("double을 경유하면 손실되는 원본(90.0000000000000001, 37.12345678901234567890)을 정확히 보존한다")
		void notViaDouble() {
			var request = read("90.0000000000000001", "127.12345678901234567890");

			assertThat(request.latitude().compareTo(BigDecimal.valueOf(90))).isPositive();
			assertThat(request.latitude().toPlainString()).isEqualTo("90.0000000000000001");
			assertThat(request.longitude().toPlainString()).isEqualTo("127.12345678901234567890");
			assertThat(Double.parseDouble("90.0000000000000001")).isEqualTo(90.0); // 대조: double이면 90이 된다
		}

		@Test
		@DisplayName("위도·경도가 서로 뒤바뀌어 읽히지 않는다")
		void fieldsAreNotSwapped() {
			var request = read("37.5", "127.25");

			assertThat(request.latitude()).isEqualByComparingTo("37.5");
			assertThat(request.longitude()).isEqualByComparingTo("127.25");
		}

		@ParameterizedTest(name = "latitude={0}")
		@ValueSource(strings = {"\"37.1\"", "\"37\"", "\"\"", "\" \"", "\"abc\"", "true", "false", "[37.1]", "[]",
			"{}", "{\"value\":37.1}", "NaN", "Infinity", "-Infinity", "+37", ".5", "37.", "037", "0x10", "1e"})
		@DisplayName("숫자 문자열·빈 문자열·boolean·배열·객체·비JSON 숫자 표기는 역직렬화 단계에서 거절한다")
		void nonNumbersRejected(String raw) {
			assertThatThrownBy(() -> read(raw, "127.1")).isInstanceOf(JacksonException.class);
			assertThatThrownBy(() -> read("37.1", raw)).isInstanceOf(JacksonException.class);
		}

		@Test
		@DisplayName("null·누락은 역직렬화를 통과하고(null) Bean Validation이 거절해야 한다")
		void nullAndMissingAreLeftToValidation() {
			assertThat(read("null", "127.1").latitude()).isNull();
			assertThat(read("37.1", "null").longitude()).isNull();
			var missingBoth = JSON.readValue(TEMPLATE.formatted(""), CreateJobPostingRequest.class);
			assertThat(missingBoth.latitude()).isNull();
			assertThat(missingBoth.longitude()).isNull();
		}

		@Test
		@DisplayName("1000자를 넘는 숫자는 Jackson 숫자 길이 제한으로 거절된다 (형식 오류 경로)")
		void overlongNumberRejected() {
			String longNumber = "37." + "1".repeat(1000);
			assertThatThrownBy(() -> read(longNumber, "127.1")).isInstanceOf(JacksonException.class);
		}

		@Test
		@DisplayName("int 범위를 넘는 지수(1e9999999999)는 BigDecimal로 표현할 수 없어 역직렬화 단계에서 거절된다")
		void exponentOverflowRejected() {
			assertThatThrownBy(() -> read("1e9999999999", "127.1")).isInstanceOf(JacksonException.class);
		}
	}

	@Nested
	@DisplayName("Bean Validation: 필수·원본 범위")
	class Validating {
		@Test
		@DisplayName("정상 좌표와 나머지 필수값을 갖춘 요청은 위반이 없다")
		void validRequest() {
			assertThat(VALIDATOR.validate(read("37.1234565", "127.9876544"))).isEmpty();
		}

		@ParameterizedTest(name = "lat={0}, lon={1}")
		@CsvSource({"-90, -180", "90, 180", "0, 0", "-0, -0", "89.9999999, 179.9999999", "9E1, 1.8E2", "1E-7, -1E-7"})
		@DisplayName("양 끝 포함 경계·0·지수 표기는 위반이 없다 (0은 누락으로 취급하지 않음)")
		void boundsValid(String latitude, String longitude) {
			assertThat(VALIDATOR.validate(read(latitude, longitude))).isEmpty();
		}

		@ParameterizedTest(name = "latitude={0}")
		@ValueSource(strings = {"90.0000004", "-90.0000004", "90.0000000000000001", "-90.0000000000000001", "91",
			"-91", "9.00000004E1", "1e2", "1e1000"})
		@DisplayName("위도 원본 범위 위반은 반올림 전에 latitude 위반 하나로 거절한다")
		void latitudeOutOfRange(String raw) {
			assertThat(paths(VALIDATOR.validate(read(raw, "127.1")))).containsExactly("latitude");
		}

		@ParameterizedTest(name = "longitude={0}")
		@ValueSource(strings = {"180.0000004", "-180.0000004", "180.0000000000000001", "-180.0000000000000001",
			"181", "-181", "1.80000004E2"})
		@DisplayName("경도 원본 범위 위반은 longitude 위반 하나로 거절한다")
		void longitudeOutOfRange(String raw) {
			assertThat(paths(VALIDATOR.validate(read("37.1", raw)))).containsExactly("longitude");
		}

		@Test
		@DisplayName("위도에 경도 범위(120)를 넣으면 latitude만 위반, 경도에 120은 정상 — 범위가 뒤바뀌지 않음")
		void boundsNotSwapped() {
			assertThat(paths(VALIDATOR.validate(read("120", "120")))).containsExactly("latitude");
		}

		@Test
		@DisplayName("null·한쪽만 입력·둘 다 누락은 해당 필드의 @NotNull 위반")
		void requiredCoordinates() {
			assertThat(paths(VALIDATOR.validate(read("null", "127.1")))).containsExactly("latitude");
			assertThat(paths(VALIDATOR.validate(read("37.1", "null")))).containsExactly("longitude");
			assertThat(paths(VALIDATOR.validate(JSON.readValue(TEMPLATE.formatted("\"latitude\":37.1,"),
				CreateJobPostingRequest.class)))).containsExactly("longitude");
			assertThat(paths(VALIDATOR.validate(JSON.readValue(TEMPLATE.formatted("\"longitude\":127.1,"),
				CreateJobPostingRequest.class)))).containsExactly("latitude");
			assertThat(paths(VALIDATOR.validate(JSON.readValue(TEMPLATE.formatted(""), CreateJobPostingRequest.class))))
				.containsExactlyInAnyOrder("latitude", "longitude");
		}

		@Test
		@DisplayName("Bean Validation은 원본 값을 바꾸지 않는다 (검증 후에도 8자리 원본 유지)")
		void validationDoesNotRound() {
			var request = read("37.12345678", "127.12345678");
			VALIDATOR.validate(request);
			assertThat(request.latitude().toPlainString()).isEqualTo("37.12345678");
			assertThat(request.longitude().toPlainString()).isEqualTo("127.12345678");
		}

		@Test
		@DisplayName("아주 작은 지수(1E-999999999)는 읽기·범위 검사는 즉시 통과한다 (정규화 단계 비용은 별도 검토)")
		void tinyExponentPassesValidationCheaply() {
			var request = read("1E-999999999", "127.1");

			assertThat(request.latitude().scale()).isEqualTo(999_999_999);
			assertThat(VALIDATOR.validate(request)).isEmpty();
		}
	}

	private static CreateJobPostingRequest read(String latitude, String longitude) {
		return JSON.readValue(TEMPLATE.formatted("\"latitude\":" + latitude + ",\"longitude\":" + longitude + ","),
			CreateJobPostingRequest.class);
	}

	private static List<String> paths(Set<ConstraintViolation<CreateJobPostingRequest>> violations) {
		return violations.stream().map(violation -> violation.getPropertyPath().toString()).toList();
	}
}

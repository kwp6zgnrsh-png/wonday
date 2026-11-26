package com.wonday.farm.job.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.UnaryOperator;

import com.wonday.farm.job.exception.JobPostingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// 엔티티 생성 경로의 원본 범위 검사와 소수점 6자리 HALF_UP 정책을 확인한다. 모든 값은 BigDecimal 문자열로 만든다.
@DisplayName("작업지 좌표 검증·정규화 (WorkLocationValidator)")
class WorkLocationValidatorTest {

	@Nested
	@DisplayName("원본 범위: 위도 -90~90, 경도 -180~180, 양 끝 포함")
	class Range {
		@ParameterizedTest(name = "위도 {0} → {1}")
		@CsvSource({"-90, -90.000000", "90, 90.000000", "-90.000000, -90.000000", "90.0000000000, 90.000000",
			"89.9999999, 90.000000", "-89.9999999, -90.000000", "89.9999994, 89.999999"})
		@DisplayName("위도 경계값과 반올림 결과가 경계가 되는 정상 원본은 허용한다")
		void latitudeBoundsAccepted(String raw, String expected) {
			assertThat(WorkLocationValidator.normalizeLatitude(new BigDecimal(raw))).isEqualTo(new BigDecimal(expected));
		}

		@ParameterizedTest(name = "경도 {0} → {1}")
		@CsvSource({"-180, -180.000000", "180, 180.000000", "179.9999999, 180.000000", "-179.9999999, -180.000000"})
		@DisplayName("경도 경계값과 반올림 결과가 경계가 되는 정상 원본은 허용한다")
		void longitudeBoundsAccepted(String raw, String expected) {
			assertThat(WorkLocationValidator.normalizeLongitude(new BigDecimal(raw))).isEqualTo(new BigDecimal(expected));
		}

		@ParameterizedTest(name = "위도 {0}")
		@ValueSource(strings = {"90.0000004", "-90.0000004", "90.0000000000000001", "-90.0000000000000001",
			"90.000001", "91", "-91", "180", "1E+2", "9.00000001E+1", "1E+1000"})
		@DisplayName("위도 원본 범위 위반은 반올림하면 90이 되더라도 INVALID_WORK_LOCATION/latitude로 거절한다")
		void latitudeOutOfRangeRejected(String raw) {
			assertRejected(() -> WorkLocationValidator.normalizeLatitude(new BigDecimal(raw)), "latitude");
		}

		@ParameterizedTest(name = "경도 {0}")
		@ValueSource(strings = {"180.0000004", "-180.0000004", "180.0000000000000001", "-180.0000000000000001",
			"181", "-181", "360", "1.80000001E+2"})
		@DisplayName("경도 원본 범위 위반은 INVALID_WORK_LOCATION/longitude로 거절한다")
		void longitudeOutOfRangeRejected(String raw) {
			assertRejected(() -> WorkLocationValidator.normalizeLongitude(new BigDecimal(raw)), "longitude");
		}

		@Test
		@DisplayName("위도는 90 초과지만 경도 범위 안인 값(예: 120)은 위도로만 거절된다 — 위도·경도 범위를 바꿔 적용하지 않는다")
		void boundsAreNotSwapped() {
			assertRejected(() -> WorkLocationValidator.normalizeLatitude(new BigDecimal("120")), "latitude");
			assertThat(WorkLocationValidator.normalizeLongitude(new BigDecimal("120"))).isEqualTo(new BigDecimal("120.000000"));
		}

		@Test
		@DisplayName("null은 위도·경도 각각의 field로 INVALID_WORK_LOCATION")
		void nullRejectedWithField() {
			assertRejected(() -> WorkLocationValidator.normalizeLatitude(null), "latitude");
			assertRejected(() -> WorkLocationValidator.normalizeLongitude(null), "longitude");
		}
	}

	@Nested
	@DisplayName("HALF_UP 6자리 반올림")
	class Rounding {
		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({
			"37.1234564, 37.123456", "37.1234565, 37.123457", "37.12345678, 37.123457",
			"-37.1234564, -37.123456", "-37.1234565, -37.123457", "127.9876544, 127.987654",
			"37.1234565000000000001, 37.123457", "37.12345649999999999, 37.123456"})
		@DisplayName("명세 예시와 긴 소수의 HALF_UP 반올림 (음수는 0에서 먼 쪽으로)")
		void halfUp(String raw, String expected) {
			var normalized = raw.startsWith("127")
				? WorkLocationValidator.normalizeLongitude(new BigDecimal(raw))
				: WorkLocationValidator.normalizeLatitude(new BigDecimal(raw));
			assertThat(normalized.toPlainString()).isEqualTo(expected);
			assertThat(normalized.scale()).isEqualTo(6);
		}

		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({"37, 37.000000", "0, 0.000000", "-0, 0.000000", "0.0, 0.000000", "37.1, 37.100000",
			"37.123456, 37.123456", "0.000001, 0.000001", "-0.000001, -0.000001", "0.0000005, 0.000001",
			"0.0000004, 0.000000", "-0.0000005, -0.000001", "1E-20, 0.000000", "3.7E+1, 37.000000", "4E+1, 40.000000"})
		@DisplayName("정수·0·6자리 이하·작은 소수·보통 지수 표기도 scale 6으로 정리한다")
		void shortAndSmallValues(String raw, String expected) {
			var normalized = WorkLocationValidator.normalizeLatitude(new BigDecimal(raw));
			assertThat(normalized.toPlainString()).isEqualTo(expected);
			assertThat(normalized.scale()).isEqualTo(6);
		}

		@ParameterizedTest(name = "극소 지수 {0} → 0.000000")
		@ValueSource(strings = {"1E-10000000", "-1E-10000000", "0E-10000000"})
		@Timeout(5)
		@DisplayName("D-1: 극소 지수 입력은 거대한 반올림 연산 없이 양쪽 좌표에서 scale 6의 0으로 정리한다")
		void tinyExponentCoordinates(String raw) {
			var original = new BigDecimal(raw);
			for (UnaryOperator<BigDecimal> normalize : java.util.List.<UnaryOperator<BigDecimal>>of(
					WorkLocationValidator::normalizeLatitude, WorkLocationValidator::normalizeLongitude)) {
				assertThat(normalize.apply(original)).isEqualTo(new BigDecimal("0.000000"));
			}
			assertThat(original.scale()).isEqualTo(10000000);
		}

		@ParameterizedTest(name = "반올림 경계 {0} → {1}")
		@CsvSource({"0.000000499999999999, 0.000000", "-0.000000499999999999, 0.000000",
			"0.0000005, 0.000001", "-0.0000005, -0.000001",
			"0.000000500000000001, 0.000001", "-0.000000500000000001, -0.000001"})
		@DisplayName("0을 바로 반환하는 경로도 양수·음수 HALF_UP 경계 전후의 결과를 유지한다")
		void halfMicroBoundaryForBothCoordinates(String raw, String expected) {
			for (UnaryOperator<BigDecimal> normalize : java.util.List.<UnaryOperator<BigDecimal>>of(
					WorkLocationValidator::normalizeLatitude, WorkLocationValidator::normalizeLongitude)) {
				assertThat(normalize.apply(new BigDecimal(raw))).isEqualTo(new BigDecimal(expected));
			}
		}

		@Test
		@DisplayName("경도도 같은 규칙: -127.9876545 → -127.987655, 0 → 0.000000")
		void longitudeRounding() {
			assertThat(WorkLocationValidator.normalizeLongitude(new BigDecimal("-127.9876545")).toPlainString()).isEqualTo("-127.987655");
			assertThat(WorkLocationValidator.normalizeLongitude(BigDecimal.ZERO).toPlainString()).isEqualTo("0.000000");
		}

		@Test
		@DisplayName("전달한 원본 BigDecimal은 변경되지 않는다 (scale·값 유지)")
		void originalIsNotChanged() {
			for (UnaryOperator<BigDecimal> normalize : java.util.List.<UnaryOperator<BigDecimal>>of(
					WorkLocationValidator::normalizeLatitude, WorkLocationValidator::normalizeLongitude)) {
				var original = new BigDecimal("37.12345678");
				var result = normalize.apply(original);
				assertThat(original.toPlainString()).isEqualTo("37.12345678");
				assertThat(original.scale()).isEqualTo(8);
				assertThat(result).isNotSameAs(original);
			}
		}
	}

	private static void assertRejected(Runnable call, String field) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(JobPostingException.class, error -> {
			assertThat(error.code()).isEqualTo("INVALID_WORK_LOCATION");
			assertThat(error.field()).isEqualTo(field);
		});
	}
}

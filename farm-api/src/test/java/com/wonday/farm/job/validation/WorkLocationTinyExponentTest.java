package com.wonday.farm.job.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.function.UnaryOperator;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

// D-1 수정(절댓값 < 0.0000005이면 setScale 없이 0.000000 반환) 독립 재검증.
// 지연은 단위 테스트 안의 메서드 호출 시간만 본다. 실제 HTTP 요청 처리 성능은 검증하지 않는다.
// 수정 전 측정치: 1E-10000000의 setScale(6, HALF_UP)이 약 8.3초. 1E-999999999처럼 더 큰 지수는 실행하지 않는다.
@DisplayName("D-1 재검증: 극소 지수 좌표 정규화")
class WorkLocationTinyExponentTest {
	private static final List<UnaryOperator<BigDecimal>> BOTH = List.of(
		WorkLocationValidator::normalizeLatitude, WorkLocationValidator::normalizeLongitude);
	// 수정 전 약 8,300ms였던 호출이다. JIT·GC 여유를 크게 두어도 수정 전 경로와는 40배 이상 차이가 난다.
	private static final Duration FAST = Duration.ofMillis(200);
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final String TEMPLATE = """
		{"title":"사과 수확 근로자 모집","description":"사과 수확과 운반 작업입니다.","cropCode":"TEST-CROP",
		 "workTypeCode":"TEST-WORK","workLocation":"충청북도 충주시 예시농장","latitude":%s,"longitude":%s,
		 "timeZone":"GMT+9","headcount":2,"dailyWage":120000,
		 "schedules":[{"workDate":"2026-11-30","plannedStartAt":"2026-11-30T08:00:00",
		   "plannedEndAt":"2026-11-30T16:00:00","breakStartAt":null,"breakEndAt":null}]}""";

	@AfterAll
	static void close() {
		FACTORY.close();
	}

	@Nested
	@DisplayName("1. 극소 지수 입력")
	class TinyExponent {
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"1E-10000000", "-1E-10000000", "0E-10000000"})
		@DisplayName("위도·경도 모두 scale 6의 0.000000을 200ms 안에 반환하고, 원본 BigDecimal은 그대로다")
		void zeroQuicklyAndOriginalUntouched(String raw) {
			for (UnaryOperator<BigDecimal> normalize : BOTH) {
				BigDecimal original = new BigDecimal(raw);
				BigInteger unscaled = original.unscaledValue();

				BigDecimal result = assertTimeout(FAST, () -> normalize.apply(original));

				assertThat(result.scale()).isEqualTo(6);
				assertThat(result.signum()).isZero();
				assertThat(result.toPlainString()).isEqualTo("0.000000");
				assertThat(original.scale()).isEqualTo(10_000_000);
				assertThat(original.unscaledValue()).isEqualTo(unscaled);
				assertThat(original.toString()).isEqualTo(new BigDecimal(raw).toString());
			}
		}

		@Test
		@DisplayName("음수 극소값의 결과에 음수 부호가 남지 않는다 (-0.000000이 아님)")
		void noNegativeZero() {
			for (UnaryOperator<BigDecimal> normalize : BOTH) {
				assertThat(normalize.apply(new BigDecimal("-1E-10000000")).toPlainString()).doesNotStartWith("-");
				assertThat(normalize.apply(new BigDecimal("-0.000000499999999999")).toPlainString()).isEqualTo("0.000000");
			}
		}
	}

	@Nested
	@DisplayName("2. HALF_UP 경계 — 빠른 0 경로와 setScale 경로의 결과가 같다")
	class Boundary {
		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({
			"0.000000499999999999, 0.000000", "-0.000000499999999999, 0.000000",
			"0.0000005, 0.000001", "-0.0000005, -0.000001",
			"0.000000500000000001, 0.000001", "-0.000000500000000001, -0.000001",
			"5E-7, 0.000001", "-5E-7, -0.000001", "5.000E-7, 0.000001", "4.99999999E-7, 0.000000",
			"0.00000050, 0.000001", "0.0000004, 0.000000", "0.0000015, 0.000002", "-0.0000015, -0.000002"})
		@DisplayName("명세 경계와 지수 표기 경계가 위도·경도에 동일하게 적용된다 (결과 scale 6)")
		void boundaries(String raw, String expected) {
			for (UnaryOperator<BigDecimal> normalize : BOTH) {
				BigDecimal result = normalize.apply(new BigDecimal(raw));
				assertThat(result.toPlainString()).isEqualTo(expected);
				assertThat(result.scale()).isEqualTo(6);
			}
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"0.0000004999999", "-0.0000004999999", "0.0000005", "-0.0000005", "0.00000050000001",
			"-0.00000050000001", "1E-1000", "-1E-1000", "0E-1000", "4.9E-7", "5.1E-7", "0", "1E-6", "-1E-6",
			"0.0000009999995", "-0.0000009999995", "37.1234565", "-37.1234565", "89.9999995", "-179.9999995"})
		@DisplayName("중간 크기 scale 값에서 결과가 기존 setScale(6, HALF_UP)과 같다 (수학적 동일성)")
		void sameAsReference(String raw) {
			BigDecimal value = new BigDecimal(raw);
			BigDecimal reference = value.setScale(6, RoundingMode.HALF_UP);
			// 위도는 원본 범위(-90~90) 안의 값에만 적용한다. 범위 밖 값(-179.9999995)은 경도만 확인한다.
			if (value.abs().compareTo(BigDecimal.valueOf(90)) <= 0) {
				assertThat(WorkLocationValidator.normalizeLatitude(value)).isEqualTo(reference);
			}
			assertThat(WorkLocationValidator.normalizeLongitude(value)).isEqualTo(reference);
		}

		@Test
		@DisplayName("0.0000005 바로 아래로 500자리 이어지는 긴 소수도 0.000000, 바로 위는 0.000001")
		void longTailAroundThreshold() {
			BigDecimal below = new BigDecimal("0.000000" + "4" + "9".repeat(500));
			BigDecimal above = new BigDecimal("0.000000" + "5" + "0".repeat(499) + "1");
			for (UnaryOperator<BigDecimal> normalize : BOTH) {
				assertThat(normalize.apply(below).toPlainString()).isEqualTo("0.000000");
				assertThat(normalize.apply(above).toPlainString()).isEqualTo("0.000001");
				assertThat(normalize.apply(below.negate()).toPlainString()).isEqualTo("0.000000");
				assertThat(normalize.apply(above.negate()).toPlainString()).isEqualTo("-0.000001");
			}
		}
	}

	@Nested
	@DisplayName("4. JSON 입력 경로: 실제 StrictCoordinateDeserializer → Bean Validation → JobPosting 생성자")
	class JsonPath {
		private final Member employer = new Member("employer", "hash", "고용주", MemberRole.EMPLOYER);

		@Test
		@DisplayName("극소 지수 JSON 숫자를 원본 그대로 읽고(scale 1천만) 엔티티에서 0.000000으로 빠르게 정규화한다")
		void tinyExponentJson() {
			CreateJobPostingRequest request = read("1E-10000000", "-1E-10000000");

			assertThat(request.latitude().unscaledValue()).isEqualTo(BigInteger.ONE);
			assertThat(request.latitude().scale()).isEqualTo(10_000_000);
			assertThat(request.longitude().unscaledValue()).isEqualTo(BigInteger.ONE.negate());
			assertThat(request.longitude().scale()).isEqualTo(10_000_000);
			assertThat(FACTORY.getValidator().validate(request)).isEmpty();

			JobPosting posting = assertTimeout(FAST, () -> new JobPosting(employer, request));

			assertThat(posting.getLatitude().toPlainString()).isEqualTo("0.000000");
			assertThat(posting.getLongitude().toPlainString()).isEqualTo("0.000000");
			assertThat(posting.getLatitude().scale()).isEqualTo(6);
			assertThat(posting.getLongitude().scale()).isEqualTo(6);
			assertThat(request.latitude().scale()).isEqualTo(10_000_000);
		}

		@Test
		@DisplayName("0E-10000000 JSON도 0.000000 (누락으로 취급하지 않음)")
		void zeroWithTinyExponent() {
			CreateJobPostingRequest request = read("0E-10000000", "0E-10000000");

			assertThat(FACTORY.getValidator().validate(request)).isEmpty();
			JobPosting posting = assertTimeout(FAST, () -> new JobPosting(employer, request));
			assertThat(posting.getLatitude()).isEqualTo(new BigDecimal("0.000000"));
			assertThat(posting.getLongitude()).isEqualTo(new BigDecimal("0.000000"));
		}

		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({"0.000000499999999999, 0.000000", "-0.000000499999999999, 0.000000",
			"0.0000005, 0.000001", "-0.0000005, -0.000001",
			"0.000000500000000001, 0.000001", "-0.000000500000000001, -0.000001"})
		@DisplayName("JSON으로 읽은 경계값도 위도·경도 모두 같은 HALF_UP 결과로 저장된다")
		void boundaryJson(String raw, String expected) {
			JobPosting posting = new JobPosting(employer, read(raw, raw));

			assertThat(posting.getLatitude().toPlainString()).isEqualTo(expected);
			assertThat(posting.getLongitude().toPlainString()).isEqualTo(expected);
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"0.00000049999999999999999999", "-0.00000049999999999999999999"})
		@DisplayName("double을 거치면 5.0E-7이 되어 0.000001로 바뀌는 원본도 BigDecimal 그대로 읽어 0.000000으로 저장한다")
		void noDoubleRoundTrip(String raw) {
			assertThat(Math.abs(Double.parseDouble(raw))).isEqualTo(5.0E-7); // 대조: double이면 경계값이 된다

			CreateJobPostingRequest request = read(raw, raw);
			assertThat(request.latitude().toPlainString()).isEqualTo(raw);
			JobPosting posting = new JobPosting(employer, request);

			assertThat(posting.getLatitude().toPlainString()).isEqualTo("0.000000");
			assertThat(posting.getLongitude().toPlainString()).isEqualTo("0.000000");
		}

		private CreateJobPostingRequest read(String latitude, String longitude) {
			return JSON.readValue(TEMPLATE.formatted(latitude, longitude), CreateJobPostingRequest.class);
		}
	}
}

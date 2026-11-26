package com.wonday.farm.job.validation;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.wonday.farm.job.exception.JobPostingException;

// HTTP 밖에서 엔티티를 생성해도 같은 정책을 적용한다. DB의 암묵적 반올림에 의존하지 않는다.
public final class WorkLocationValidator {
	private static final BigDecimal HALF_MICRO_DEGREE = new BigDecimal("0.0000005");
	private static final BigDecimal ZERO_COORDINATE = new BigDecimal("0.000000");

	private WorkLocationValidator() {}

	public static BigDecimal normalizeLatitude(BigDecimal value) {
		return normalize(value, 90, "latitude");
	}

	public static BigDecimal normalizeLongitude(BigDecimal value) {
		return normalize(value, 180, "longitude");
	}

	private static BigDecimal normalize(BigDecimal value, int bound, String field) {
		BigDecimal maximum = BigDecimal.valueOf(bound);
		// 90.0000004를 90으로 만든 뒤 통과시키지 않고, 원본 값부터 거절한다.
		if (value == null || value.compareTo(maximum.negate()) < 0 || value.compareTo(maximum) > 0) {
			throw new JobPostingException("INVALID_WORK_LOCATION",
				"좌표는 필수이며 위도는 -90~90, 경도는 -180~180 범위여야 합니다.", field);
		}
		// 절댓값이 반올림 경계보다 작으면 HALF_UP 결과는 항상 소수점 6자리의 0이다.
		// 1E-10000000 같은 값에 setScale을 호출해 거대한 10의 거듭제곱을 만들지 않는다.
		// 경계값 ±0.0000005는 이 분기에 포함하지 않아 기존 HALF_UP 규칙을 유지한다.
		if (value.abs().compareTo(HALF_MICRO_DEGREE) < 0) {
			return ZERO_COORDINATE;
		}
		return value.setScale(6, RoundingMode.HALF_UP);
	}
}

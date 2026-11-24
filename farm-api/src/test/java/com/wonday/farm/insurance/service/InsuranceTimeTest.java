package com.wonday.farm.insurance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.wonday.farm.insurance.exception.InsuranceApiException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

// 저장된 현지 약정 시각(local)과 UTC 저장 시각(utc)을 약정 시간대의 오프셋으로 표현하는 규칙.
// 잘못 저장된 시간대·누락 값은 추측하지 않고 503 SERVICE_UNAVAILABLE로 처리하는 것이 현재 코드의 계약이다.
class InsuranceTimeTest {
	private static final ZoneId GMT9 = ZoneId.of("GMT+9");

	@Nested
	class Zone {
		@Test
		void gmtPlus9HasPlusNineOffset() {
			assertThat(InsuranceTime.zone("GMT+9").getRules().getOffset(java.time.Instant.EPOCH))
				.isEqualTo(ZoneOffset.ofHours(9));
		}

		@ParameterizedTest(name = "zone=\"{0}\"")
		@NullSource
		@ValueSource(strings = {"", " ", "Mars/Olympus", "GMT+99", "+25:00", "not a zone"})
		void missingOrInvalidStoredZoneIsUnavailable(String value) {
			assertUnavailable(() -> InsuranceTime.zone(value));
		}
	}

	@Nested
	class LocalAgreement {
		@Test
		void attachesZoneOffsetWithoutShiftingTheWallClock() {
			assertThat(InsuranceTime.local(LocalDateTime.parse("2026-11-30T08:00"), GMT9))
				.isEqualTo(OffsetDateTime.parse("2026-11-30T08:00+09:00"));
		}

		@Test
		void missingValueIsUnavailable() {
			assertUnavailable(() -> InsuranceTime.local(null, GMT9));
		}

		@Test
		void ambiguousOrNonexistentLocalTimeIsUnavailable() {
			// GMT+9는 서머타임이 없어 도달하지 않는 분기다. 서머타임 지역 시간대가 저장된 경우의 계약을 고정한다.
			ZoneId newYork = ZoneId.of("America/New_York");

			assertUnavailable(() -> InsuranceTime.local(LocalDateTime.parse("2026-03-08T02:30"), newYork));
			assertUnavailable(() -> InsuranceTime.local(LocalDateTime.parse("2026-11-01T01:30"), newYork));
		}
	}

	@Nested
	class UtcStored {
		@Test
		void convertsUtcInstantToZoneOffset() {
			assertThat(InsuranceTime.utc(LocalDateTime.parse("2026-11-29T23:30:00.123456"), GMT9))
				.isEqualTo(OffsetDateTime.parse("2026-11-30T08:30:00.123456+09:00"))
				.satisfies(value -> assertThat(value.getOffset()).isEqualTo(ZoneOffset.ofHours(9)));
		}

		@Test
		void missingValueIsUnavailable() {
			assertUnavailable(() -> InsuranceTime.utc(null, GMT9));
		}
	}

	private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(error.code()).isEqualTo("SERVICE_UNAVAILABLE");
		});
	}
}

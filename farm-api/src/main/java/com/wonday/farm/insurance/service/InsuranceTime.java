package com.wonday.farm.insurance.service;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.wonday.farm.insurance.exception.InsuranceApiException;

public final class InsuranceTime {
	private InsuranceTime() {}

	public static ZoneId zone(String value) {
		try {
			if (value == null || value.isBlank()) throw InsuranceApiException.unavailable();
			return ZoneId.of(value);
		} catch (DateTimeException exception) {
			throw InsuranceApiException.unavailable();
		}
	}

	public static OffsetDateTime local(LocalDateTime value, ZoneId zone) {
		if (value == null || zone.getRules().getValidOffsets(value).size() != 1) {
			throw InsuranceApiException.unavailable();
		}
		return value.atZone(zone).toOffsetDateTime();
	}

	public static OffsetDateTime utc(LocalDateTime value, ZoneId zone) {
		if (value == null) throw InsuranceApiException.unavailable();
		return value.toInstant(ZoneOffset.UTC).atZone(zone).toOffsetDateTime();
	}
}

package com.wonday.insurance.claim;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

record ClaimWeatherStationView(long claimCaseId, long stationId, String provider, String stationCode, String name,
	String address, BigDecimal latitude, BigDecimal longitude, BigDecimal distanceMeters, String selectionRule, OffsetDateTime selectedAt) {}

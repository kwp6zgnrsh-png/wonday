package com.wonday.insurance.weather;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// RECEIVED는 수신 성공이다. 당일의 전체 144개 구간 확보나 모델 입력 적격성을 의미하지 않는다.
record WeatherCollectionView(long id, String stationCode, LocalDate observationDate, String state,
		int observationCount, String payloadSha256, OffsetDateTime collectedAt) {
	static WeatherCollectionView from(WeatherCollection value) {
		return new WeatherCollectionView(value.getId(), value.getStationCode(), value.getObservationDate(),
			value.getObservationCount() == 0 ? "EMPTY" : "RECEIVED", value.getObservationCount(),
			value.getPayloadSha256(), value.getCollectedAt().atOffset(ZoneOffset.UTC));
	}
}

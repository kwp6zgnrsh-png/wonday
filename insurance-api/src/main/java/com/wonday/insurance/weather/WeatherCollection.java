package com.wonday.insurance.weather;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "weather_collection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WeatherCollection {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "station_code", nullable = false, updatable = false, length = 10) private String stationCode;
	@Column(name = "observation_date", nullable = false, updatable = false) private LocalDate observationDate;
	@Column(name = "observation_count", nullable = false, updatable = false) private int observationCount;
	@Column(name = "payload_sha256", nullable = false, updatable = false, length = 64) private String payloadSha256;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_pages_json", nullable = false, updatable = false, columnDefinition = "JSON") private String payloadPagesJson;
	@Column(name = "collected_at", nullable = false, updatable = false) private LocalDateTime collectedAt;
	WeatherCollection(String stationCode, LocalDate date, int count, String hash, String pages, LocalDateTime at) {
		this.stationCode = stationCode; this.observationDate = date; this.observationCount = count;
		this.payloadSha256 = hash; this.payloadPagesJson = pages; this.collectedAt = at;
	}
}

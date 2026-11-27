package com.wonday.insurance.weather;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "weather_station")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WeatherStation {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(nullable = false, length = 64) private String provider;
	@Column(name = "station_code", nullable = false, length = 40) private String stationCode;
	@Column(nullable = false, length = 150) private String name;
	@Column(nullable = false, precision = 9, scale = 6) private BigDecimal latitude;
	@Column(nullable = false, precision = 9, scale = 6) private BigDecimal longitude;
	@Column(length = 500) private String address;
	@Column(nullable = false) private boolean enabled;
	@Column(name = "metadata_synced_at") private LocalDateTime metadataSyncedAt;
}

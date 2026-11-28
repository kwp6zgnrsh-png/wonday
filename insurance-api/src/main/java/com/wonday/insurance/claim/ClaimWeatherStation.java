package com.wonday.insurance.claim;

import com.wonday.insurance.weather.WeatherStationCatalog;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 접수 1건당 최초 선정 결과 1건. 수정 메서드를 두지 않아 재요청으로 덮어쓰지 않는다. */
@Entity
@Table(name = "claim_weather_station")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ClaimWeatherStation {
	@Id @Column(name = "claim_case_id") private Long claimCaseId;
	@Column(name = "station_id", nullable = false) private long stationId;
	@Column(name = "distance_meters", nullable = false, precision = 12, scale = 3) private BigDecimal distanceMeters;
	@Column(name = "selection_rule", nullable = false, length = 64) private String selectionRule;
	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "selected_at", nullable = false) private LocalDateTime selectedAt;

	ClaimWeatherStation(long claimCaseId, WeatherStationCatalog.Selection selection, LocalDateTime selectedAt) {
		this.claimCaseId = claimCaseId; this.stationId = selection.stationId();
		this.distanceMeters = selection.distanceMeters(); this.selectionRule = selection.selectionRule(); this.selectedAt = selectedAt;
	}
}

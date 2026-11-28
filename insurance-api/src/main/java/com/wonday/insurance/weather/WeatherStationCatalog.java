package com.wonday.insurance.weather;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고정 관측소 DB만 읽는다. 접수 처리 중 기상 API를 호출하지 않는다. */
@Service
@RequiredArgsConstructor
public class WeatherStationCatalog {
	public static final String RULE = "nearest-gyeonggi-haversine-v1";
	private final WeatherStationRepository stations;
	private final GyeonggiStations scope;

	@Transactional(readOnly = true)
	public Selection nearest(BigDecimal latitude, BigDecimal longitude) {
		StationCoordinates.validate(latitude, longitude);
		var all = stations.findByProviderAndEnabledTrueOrderByStationCode("RDA").stream()
			.filter(station -> scope.names().containsKey(station.getStationCode())).toList();
		if (all.size() != scope.names().size()) throw new WeatherException(HttpStatus.SERVICE_UNAVAILABLE,
			"STATION_METADATA_INCOMPLETE", "All configured station coordinates must be present before selection");
		// 반올림 전 거리로 비교하며, 동일 거리일 때만 관측소 코드 오름차순을 사용한다.
		var chosen = all.stream().map(station -> new Candidate(station,
			StationCoordinates.distanceMeters(latitude, longitude, station.getLatitude(), station.getLongitude())))
			.min(Comparator.comparingDouble(Candidate::distance).thenComparing(value -> value.station().getStationCode())).orElseThrow();
		return new Selection(chosen.station().getId(), chosen.station().getStationCode(),
			BigDecimal.valueOf(chosen.distance()).setScale(3, RoundingMode.HALF_UP), RULE);
	}
	private record Candidate(WeatherStation station, double distance) {}
	public record Selection(long stationId, String stationCode, BigDecimal distanceMeters, String selectionRule) {}
}

package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class WeatherStationService {
	private final MemberAccessService members;
	private final GyeonggiStations scope;
	private final WeatherStationRepository stations;
	private final RdaWeatherClient provider;
	private final WeatherStationWriter writer;

	List<StationView> sync(long memberId) {
		members.requireAdmin(memberId);
		// 현재 범위의 24개가 모두 확인된 경우에만 저장한다. 일부 지점 조회 실패를 정상 목록으로 대체하지 않는다.
		var found = provider.stations(scope.names().keySet());
		writer.save(memberId, found);
		return list(memberId);
	}

	@Transactional(readOnly = true)
	public List<StationView> list(long memberId) {
		members.requireMember(memberId);
		Map<String, WeatherStation> registered = registered();
		return scope.names().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry -> {
			var value = registered.get(entry.getKey());
			return value == null ? new StationView(entry.getKey(), entry.getValue(), false, null, null, null, null)
				: view(value);
		}).toList();
	}

	@Transactional(readOnly = true)
	public Selection nearest(long memberId, BigDecimal latitude, BigDecimal longitude) {
		members.requireMember(memberId);
		StationCoordinates.validate(latitude, longitude);
		var all = registered().values();
		if (all.size() != scope.names().size()) throw new WeatherException(HttpStatus.SERVICE_UNAVAILABLE,
			"STATION_METADATA_INCOMPLETE", "All configured station coordinates must be synchronized before selection");
		// 거리와 코드로만 선정한다. 결측·모델 점수에 따라 유리한 지점으로 바꾸지 않는다.
		var chosen = all.stream().map(station -> new Candidate(station,
			StationCoordinates.distanceMeters(latitude, longitude, station.getLatitude(), station.getLongitude())))
			.min(Comparator.comparingDouble(Candidate::distance).thenComparing(value -> value.station().getStationCode())).orElseThrow();
		return new Selection(view(chosen.station()), BigDecimal.valueOf(chosen.distance()).setScale(3, RoundingMode.HALF_UP),
			"nearest-gyeonggi-haversine-v1");
	}
	private Map<String, WeatherStation> registered() {
		return stations.findByProviderAndEnabledTrueOrderByStationCode("RDA").stream()
			.filter(station -> scope.names().containsKey(station.getStationCode()))
			.collect(Collectors.toMap(WeatherStation::getStationCode, Function.identity()));
	}
	private static StationView view(WeatherStation value) {
		return new StationView(value.getStationCode(), value.getName(), true, value.getAddress(), value.getLatitude(), value.getLongitude(),
			value.getMetadataSyncedAt() == null ? null : value.getMetadataSyncedAt().atOffset(ZoneOffset.UTC));
	}
	record Candidate(WeatherStation station, double distance) {}
	record StationView(String stationCode, String name, boolean coordinatesAvailable, String address,
		BigDecimal latitude, BigDecimal longitude, OffsetDateTime metadataSyncedAt) {}
	record Selection(StationView station, BigDecimal distanceMeters, String selectionRule) {}
}

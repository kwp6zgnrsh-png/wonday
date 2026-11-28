package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class WeatherStationService {
	private final MemberAccessService members;
	private final GyeonggiStations scope;
	private final WeatherStationRepository stations;
	private final WeatherStationCatalog catalog;

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
		var chosen = catalog.nearest(latitude, longitude);
		var station = stations.findById(chosen.stationId()).orElseThrow();
		return new Selection(view(station), chosen.distanceMeters(), chosen.selectionRule());
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
	record StationView(String stationCode, String name, boolean coordinatesAvailable, String address,
		BigDecimal latitude, BigDecimal longitude, OffsetDateTime metadataSyncedAt) {}
	record Selection(StationView station, BigDecimal distanceMeters, String selectionRule) {}
}

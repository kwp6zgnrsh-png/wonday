package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class WeatherStationWriter {
	private final MemberAccessService members;
	private final WeatherStationRepository stations;
	private final Clock clock;
	@Transactional
	public void save(long memberId, List<RdaWeatherClient.Station> source) {
		members.lockAdmin(memberId);
		LocalDateTime at = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		for (var value : source) {
			WeatherStation station = stations.findByProviderAndStationCode("RDA", value.code()).orElseGet(() -> new WeatherStation(value.code()));
			station.refresh(value, at); stations.save(station);
		}
		stations.flush();
	}
}

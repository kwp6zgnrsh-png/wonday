package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
		// 서로 다른 관리자도 같은 순서로 행을 잠근다. 외부 응답 순서가 달라도 교차 잠금에 의한 교착을 피한다.
		for (var value : source.stream().sorted(Comparator.comparing(RdaWeatherClient.Station::code)).toList()) {
			stations.upsert(value.code(), value.name(), value.address(),
				value.latitude().setScale(6, RoundingMode.HALF_UP), value.longitude().setScale(6, RoundingMode.HALF_UP), at);
		}
	}
}

package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class WeatherCollectionWriter {
	private final MemberAccessService members;
	private final WeatherCollectionRepository collections;
	private final Clock clock;
	@Transactional
	public WeatherCollectionView save(long memberId, String code, LocalDate date, int count, String hash, String pages) {
		members.lockAdmin(memberId);
		var found = collections.findByStationCodeAndObservationDateAndPayloadSha256(code, date, hash);
		if (found.isPresent()) return WeatherCollectionView.from(found.get());
		var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		return WeatherCollectionView.from(collections.saveAndFlush(new WeatherCollection(code, date, count, hash, pages, now)));
	}
}

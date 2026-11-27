package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class WeatherCollectionService {
	private final MemberAccessService members;
	private final GyeonggiStations scope;
	private final RdaWeatherClient provider;
	private final WeatherCollectionWriter writer;
	private final WeatherCollectionRepository collections;
	private final Clock clock;
	private final ObjectMapper json;

	WeatherCollectionView collect(long memberId, String code, LocalDate date) {
		members.requireAdmin(memberId);
		validate(code, date);
		// 원격 호출·모든 페이지 검증을 먼저 끝낸다. 실패 시 부분 원본이나 성공 기록을 저장하지 않는다.
		var batch = provider.observations(code, date);
		String pages = json.writeValueAsString(batch.pages());
		String hash = digest(pages);
		try { return writer.save(memberId, code, date, batch.itemCount(), hash, pages); }
		catch (DataIntegrityViolationException exception) {
			if (!(exception.getCause() instanceof ConstraintViolationException violation)
				|| violation.getConstraintName() == null || !violation.getConstraintName().contains("uq_weather_source")) throw exception;
			// 다른 관리자의 동시 수집도 같은 관측소·날짜·원본이면 한 건을 반환한다.
			members.requireAdmin(memberId);
			return collections.findByStationCodeAndObservationDateAndPayloadSha256(code, date, hash)
				.map(WeatherCollectionView::from).orElseThrow(() -> exception);
		}
	}

	@Transactional(readOnly = true)
	public List<WeatherCollectionView> list(long memberId, String code, LocalDate date) {
		members.requireAdmin(memberId); validate(code, date);
		return collections.findTop100ByStationCodeAndObservationDateOrderByIdDesc(code, date).stream().map(WeatherCollectionView::from).toList();
	}

	@Transactional(readOnly = true)
	public Detail detail(long memberId, long id) {
		members.requireAdmin(memberId);
		var row = collections.findById(id).orElseThrow(() -> new WeatherException(HttpStatus.NOT_FOUND, "WEATHER_COLLECTION_NOT_FOUND", "Weather collection not found"));
		return new Detail(WeatherCollectionView.from(row), json.readValue(row.getPayloadPagesJson(), new tools.jackson.core.type.TypeReference<List<String>>() {}));
	}

	private void validate(String code, LocalDate date) {
		scope.require(code);
		if (date == null || date.isAfter(LocalDate.ofInstant(clock.instant(), ZoneId.of("GMT+9")))) {
			throw new WeatherException(HttpStatus.BAD_REQUEST, "INVALID_WEATHER_DATE", "A present or past GMT+9 date is required");
		}
	}
	private static String digest(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
	record Detail(WeatherCollectionView collection, List<String> pages) {}
}

package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class WeatherCollectionService {
	private final MemberAccessService members;
	private final GyeonggiStations scope;
	private final RdaWeatherClient provider;
	private final WeatherCollectionJobWriter jobs;
	private final WeatherCollectionRepository collections;
	private final Clock clock;
	private final ObjectMapper json;

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public WeatherCollectionView collect(long memberId, String code, LocalDate date) {
		return collect(memberId, code, date, null);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public WeatherCollectionView collect(long memberId, String code, LocalDate date, String rawKey) {
		members.requireAdmin(memberId);
		validate(code, date);
		return execute(jobs.collect(memberId, code, date, rawKey == null ? UUID.randomUUID().toString() : requestKey(rawKey)));
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public WeatherCollectionView retry(long memberId, long jobId, String rawKey) {
		members.requireAdmin(memberId);
		return execute(jobs.retry(memberId, jobId, requestKey(rawKey)));
	}

	private WeatherCollectionView execute(WeatherCollectionJobWriter.Ticket ticket) {
		if (!ticket.execute()) {
			members.requireAdmin(ticket.memberId());
			return switch (ticket.state()) {
				case SUCCEEDED -> collections.findById(ticket.collectionId()).map(WeatherCollectionView::from)
					.orElseThrow(() -> WeatherCollectionFailure.WEATHER_COLLECTION_STORAGE_UNAVAILABLE.exception(ticket.jobId(), ticket.attemptId()));
				case FAILED -> throw WeatherCollectionFailure.valueOf(ticket.errorCode()).exception(ticket.jobId(), ticket.attemptId());
				case RUNNING -> throw new WeatherException(HttpStatus.CONFLICT, "WEATHER_COLLECTION_IN_PROGRESS", "This request is already running", ticket.jobId(), ticket.attemptId());
			};
		}
		try {
			// RUNNING은 먼저 커밋한다. 원격 호출 중에는 DB 트랜잭션·행 잠금을 유지하지 않는다.
			var batch = provider.observations(ticket.stationCode(), ticket.date());
			String pages = json.writeValueAsString(batch.pages());
			String hash = digest(pages);
			try { return jobs.complete(ticket, batch.itemCount(), hash, pages, false); }
			catch (DataIntegrityViolationException collision) {
				if (!(collision.getCause() instanceof ConstraintViolationException violation)
					|| violation.getConstraintName() == null || !violation.getConstraintName().contains("uq_weather_source")) throw collision;
				// 이전 서버 등에서 같은 원본을 먼저 저장한 경우도 실패한 트랜잭션 밖에서 재사용한다.
				return jobs.complete(ticket, batch.itemCount(), hash, pages, true);
			}
		} catch (RuntimeException error) {
			var reason = WeatherCollectionFailure.from(error);
			try { jobs.fail(ticket, reason); }
			catch (RuntimeException unavailable) {
				// DB 자체가 중단되면 시작 이력은 남는다. 기한 만료 후 수동 재시도로 복구한다.
				reason = WeatherCollectionFailure.WEATHER_COLLECTION_STORAGE_UNAVAILABLE;
			}
			throw reason.exception(ticket.jobId(), ticket.attemptId());
		}
	}
	private static String requestKey(String raw) {
		if (raw == null || !raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw new WeatherException(HttpStatus.BAD_REQUEST, "INVALID_WEATHER_REQUEST_KEY", "Idempotency-Key must be a canonical UUID");
		}
		return UUID.fromString(raw).toString();
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

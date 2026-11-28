package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class WeatherCollectionJobService {
	private final MemberAccessService members;
	private final WeatherCollectionJobRepository jobs;
	private final WeatherCollectionAttemptRepository attempts;
	private final GyeonggiStations scope;
	private final Clock clock;

	public Page<JobView> list(long memberId, String state, String code, LocalDate date, Long beforeId, int limit) {
		members.requireAdmin(memberId); validatePage(beforeId, limit);
		if (code != null) scope.require(code);
		if (date != null && date.isAfter(LocalDate.ofInstant(clock.instant(), ZoneId.of("GMT+9")))) throw invalidQuery();
		WeatherCollectionJob.State parsed = null;
		try { if (state != null) parsed = WeatherCollectionJob.State.valueOf(state); }
		catch (IllegalArgumentException invalid) { throw invalidQuery(); }
		var rows = jobs.history(parsed, code, date, beforeId, PageRequest.of(0, limit + 1));
		var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		return page(rows, limit, row -> view(row, now), WeatherCollectionJob::getId);
	}
	public JobView detail(long memberId, long id) {
		members.requireAdmin(memberId);
		return view(required(id), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
	}
	public Page<AttemptView> attempts(long memberId, long jobId, Long beforeId, int limit) {
		members.requireAdmin(memberId); validatePage(beforeId, limit); required(jobId);
		return page(attempts.history(jobId, beforeId, PageRequest.of(0, limit + 1)), limit, AttemptView::from, WeatherCollectionAttempt::getId);
	}
	private WeatherCollectionJob required(long id) {
		return jobs.findById(id).orElseThrow(() -> new WeatherException(HttpStatus.NOT_FOUND, "WEATHER_COLLECTION_JOB_NOT_FOUND", "Weather collection job not found"));
	}
	private static void validatePage(Long beforeId, int limit) { if ((beforeId != null && beforeId <= 0) || limit < 1 || limit > 100) throw invalidQuery(); }
	private static WeatherException invalidQuery() { return new WeatherException(HttpStatus.BAD_REQUEST, "INVALID_WEATHER_JOB_QUERY", "Invalid weather collection history query"); }
	private static JobView view(WeatherCollectionJob row, LocalDateTime now) {
		return new JobView(row.getId(), row.getStationCode(), row.getObservationDate(), row.getState(), row.getAttemptCount(),
			row.getLastErrorCode(), row.getLastCollectionId(), at(row.getLeaseExpiresAt()), row.retryable(now), at(row.getCreatedAt()), at(row.getUpdatedAt()));
	}
	private static OffsetDateTime at(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
	private static <T,V> Page<V> page(List<T> rows, int limit, Function<T,V> convert, Function<T,Long> id) {
		boolean more = rows.size() > limit;
		var visible = rows.subList(0, Math.min(rows.size(), limit));
		return new Page<>(visible.stream().map(convert).toList(), more, more ? id.apply(visible.getLast()) : null);
	}
	record Page<T>(List<T> items, boolean hasMore, Long nextBeforeId) {}
	record JobView(long id, String stationCode, LocalDate observationDate, WeatherCollectionJob.State state, int attemptCount,
		String lastErrorCode, Long lastCollectionId, OffsetDateTime leaseExpiresAt, boolean retryable, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
	record AttemptView(long id, int attemptNumber, long requestedByMemberId, WeatherCollectionAttempt.Trigger triggerType,
		WeatherCollectionJob.State state, OffsetDateTime startedAt, OffsetDateTime finishedAt, String errorCode, Long collectionId) {
		static AttemptView from(WeatherCollectionAttempt row) {
			return new AttemptView(row.getId(), row.getAttemptNumber(), row.getRequestedByMemberId(), row.getTriggerType(), row.getState(),
				at(row.getStartedAt()), at(row.getFinishedAt()), row.getErrorCode(), row.getCollectionId());
		}
	}
}

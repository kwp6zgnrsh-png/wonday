package com.wonday.insurance.weather;

import com.wonday.insurance.member.MemberAccessService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
class WeatherCollectionJobWriter {
	private final MemberAccessService members;
	private final WeatherCollectionJobRepository jobs;
	private final WeatherCollectionAttemptRepository attempts;
	private final WeatherCollectionWriter originals;
	private final WeatherCollectionRepository collections;
	private final EntityManager entities;
	private final Clock clock;
	private final WeatherProperties properties;

	public Ticket collect(long memberId, String code, LocalDate date, String key) {
		freshContext(); members.lockAdmin(memberId);
		LocalDateTime now = now();
		// 최초 생성도 UNIQUE 키로 직렬화한다. 0회인 임시 행은 같은 트랜잭션에서 즉시 첫 실행으로 바뀐다.
		jobs.ensureTarget(code, date, now);
		return start(memberId, jobs.lockTarget(code, date).orElseThrow(), key, WeatherCollectionAttempt.Trigger.COLLECT, now);
	}
	public Ticket retry(long memberId, long jobId, String key) {
		freshContext(); members.lockAdmin(memberId);
		return start(memberId, required(jobId), key, WeatherCollectionAttempt.Trigger.RETRY, now());
	}
	private Ticket start(long memberId, WeatherCollectionJob job, String key, WeatherCollectionAttempt.Trigger trigger, LocalDateTime now) {
		var previous = attempts.findByRequestedByMemberIdAndRequestKey(memberId, key);
		if (previous.isPresent()) {
			var attempt = previous.get();
			if (attempt.getJobId() != job.getId() || attempt.getTriggerType() != trigger) {
				// 새 대상의 임시 행은 롤백되므로, 존재하지 않을 수 있는 작업 ID를 오류에 포함하지 않는다.
				throw new WeatherException(HttpStatus.CONFLICT, "WEATHER_IDEMPOTENCY_KEY_REUSED", "Request key was used for a different collection request");
			}
			if (attempt.getAttemptNumber() == job.getAttemptCount()) expire(job, now);
			return ticket(job, attempt, false);
		}
		expire(job, now);
		if (job.getAttemptCount() > 0 && job.getState() == WeatherCollectionJob.State.RUNNING) {
			throw conflict("WEATHER_COLLECTION_IN_PROGRESS", "Weather collection is already running for this station and date", job.getId());
		}
		if (trigger == WeatherCollectionAttempt.Trigger.RETRY && job.getState() != WeatherCollectionJob.State.FAILED) {
			throw conflict("WEATHER_COLLECTION_NOT_RETRYABLE", "Only failed or expired collections can be retried", job.getId());
		}
		job.start(now, now.plus(properties.collectionRunTimeout()).truncatedTo(ChronoUnit.MICROS));
		var attempt = attempts.saveAndFlush(new WeatherCollectionAttempt(job.getId(), job.getAttemptCount(), memberId, key, trigger, now));
		return ticket(job, attempt, true);
	}

	public WeatherCollectionView complete(Ticket ticket, int count, String hash, String pages, boolean existingOnly) {
		freshContext(); members.lockAdmin(ticket.memberId());
		var job = required(ticket.jobId());
		LocalDateTime now = now();
		// 실행 번호를 fencing 값으로 사용한다. 만료 후 돌아온 이전 요청이 새 실행 결과를 덮어쓰지 못한다.
		if (job.getState() != WeatherCollectionJob.State.RUNNING || job.getAttemptCount() != ticket.number()) {
			throw WeatherCollectionFailure.WEATHER_COLLECTION_RUN_SUPERSEDED.exception(job.getId(), ticket.attemptId());
		}
		if (job.expired(now)) throw WeatherCollectionFailure.WEATHER_COLLECTION_RUN_EXPIRED.exception(job.getId(), ticket.attemptId());
		WeatherCollectionView stored = existingOnly
			? collections.findByStationCodeAndObservationDateAndPayloadSha256(job.getStationCode(), job.getObservationDate(), hash)
				.map(WeatherCollectionView::from).orElseThrow(() -> WeatherCollectionFailure.WEATHER_COLLECTION_STORAGE_UNAVAILABLE.exception(job.getId(), ticket.attemptId()))
			: originals.save(ticket.memberId(), job.getStationCode(), job.getObservationDate(), count, hash, pages);
		current(job).succeed(stored.id(), now);
		job.succeed(stored.id(), now);
		// 원본과 성공 이력은 같은 트랜잭션에서 확정한다. 한쪽만 커밋하지 않는다.
		jobs.flush();
		return stored;
	}

	public void fail(Ticket ticket, WeatherCollectionFailure reason) {
		freshContext();
		var job = required(ticket.jobId());
		// 권한이 바뀌어도 내부 실패 기록은 남긴다. 이 메서드는 원격 호출이나 원본 저장을 하지 않는다.
		if (job.getState() == WeatherCollectionJob.State.RUNNING && job.getAttemptCount() == ticket.number()) {
			LocalDateTime now = now();
			current(job).fail(reason.name(), now); job.fail(reason.name(), now); jobs.flush();
		}
	}
	private void expire(WeatherCollectionJob job, LocalDateTime now) {
		if (job.getAttemptCount() > 0 && job.expired(now)) {
			String code = WeatherCollectionFailure.WEATHER_COLLECTION_RUN_EXPIRED.name();
			current(job).fail(code, now); job.fail(code, now);
		}
	}
	private WeatherCollectionAttempt current(WeatherCollectionJob job) {
		return attempts.findByJobIdAndAttemptNumber(job.getId(), job.getAttemptCount()).orElseThrow();
	}
	private WeatherCollectionJob required(long id) {
		return jobs.lockJob(id).orElseThrow(() -> new WeatherException(HttpStatus.NOT_FOUND, "WEATHER_COLLECTION_JOB_NOT_FOUND", "Weather collection job not found"));
	}
	private void freshContext() {
		// 외부 조회 전후에 같은 웹 영속성 컨텍스트가 남아 있어도 최신 DB 권한·실행 상태를 다시 읽는다.
		entities.clear();
	}
	private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
	private static WeatherException conflict(String code, String message, long jobId) { return new WeatherException(HttpStatus.CONFLICT, code, message, jobId, null); }
	private static Ticket ticket(WeatherCollectionJob job, WeatherCollectionAttempt attempt, boolean execute) {
		return new Ticket(job.getId(), attempt.getId(), attempt.getAttemptNumber(), job.getStationCode(), job.getObservationDate(),
			attempt.getRequestedByMemberId(), attempt.getState(), attempt.getErrorCode(), attempt.getCollectionId(), execute);
	}
	record Ticket(long jobId, long attemptId, int number, String stationCode, LocalDate date, long memberId,
		WeatherCollectionJob.State state, String errorCode, Long collectionId, boolean execute) {}
}

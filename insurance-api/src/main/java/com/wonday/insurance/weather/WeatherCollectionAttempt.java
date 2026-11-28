package com.wonday.insurance.weather;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "weather_collection_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WeatherCollectionAttempt {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "job_id", nullable = false, updatable = false) private long jobId;
	@Column(name = "attempt_number", nullable = false, updatable = false) private int attemptNumber;
	@Column(name = "requested_by_member_id", nullable = false, updatable = false) private long requestedByMemberId;
	@Column(name = "request_key", nullable = false, updatable = false, length = 36) private String requestKey;
	@Enumerated(EnumType.STRING) @Column(name = "trigger_type", nullable = false, updatable = false, length = 16) private Trigger triggerType;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private WeatherCollectionJob.State state;
	@Column(name = "started_at", nullable = false, updatable = false) private LocalDateTime startedAt;
	@Column(name = "finished_at") private LocalDateTime finishedAt;
	@Column(name = "error_code", length = 64) private String errorCode;
	@Column(name = "collection_id") private Long collectionId;

	WeatherCollectionAttempt(long jobId, int number, long memberId, String key, Trigger trigger, LocalDateTime now) {
		this.jobId = jobId; attemptNumber = number; requestedByMemberId = memberId; requestKey = key;
		triggerType = trigger; state = WeatherCollectionJob.State.RUNNING; startedAt = now;
	}
	void succeed(long collectionId, LocalDateTime now) {
		state = WeatherCollectionJob.State.SUCCEEDED; this.collectionId = collectionId; finishedAt = now;
	}
	void fail(String code, LocalDateTime now) {
		state = WeatherCollectionJob.State.FAILED; errorCode = code; finishedAt = now;
	}
	enum Trigger { COLLECT, RETRY }
}

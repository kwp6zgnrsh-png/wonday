package com.wonday.insurance.weather;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "weather_collection_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class WeatherCollectionJob {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "station_code", nullable = false, length = 10) private String stationCode;
	@Column(name = "observation_date", nullable = false) private LocalDate observationDate;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private State state;
	@Column(name = "attempt_count", nullable = false) private int attemptCount;
	@Column(name = "lease_expires_at") private LocalDateTime leaseExpiresAt;
	@Column(name = "last_error_code", length = 64) private String lastErrorCode;
	@Column(name = "last_collection_id") private Long lastCollectionId;
	@Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
	@Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;

	void start(LocalDateTime now, LocalDateTime deadline) {
		attemptCount = Math.addExact(attemptCount, 1);
		state = State.RUNNING; leaseExpiresAt = deadline; lastErrorCode = null; updatedAt = now;
	}
	void succeed(long collectionId, LocalDateTime now) {
		state = State.SUCCEEDED; leaseExpiresAt = null; lastErrorCode = null;
		lastCollectionId = collectionId; updatedAt = now;
	}
	void fail(String errorCode, LocalDateTime now) {
		state = State.FAILED; leaseExpiresAt = null; lastErrorCode = errorCode; updatedAt = now;
	}
	boolean expired(LocalDateTime now) {
		return state == State.RUNNING && leaseExpiresAt != null && !leaseExpiresAt.isAfter(now);
	}
	boolean retryable(LocalDateTime now) { return state == State.FAILED || expired(now); }
	enum State { RUNNING, SUCCEEDED, FAILED }
}

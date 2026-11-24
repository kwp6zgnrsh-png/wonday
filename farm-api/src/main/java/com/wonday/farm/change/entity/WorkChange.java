package com.wonday.farm.change.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "work_change")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkChange {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "assignment_id", nullable = false, unique = true)
	private long assignmentId;
	@Column(name = "actor_id", nullable = false)
	private long actorId;
	@Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false, length = 32)
	private Type changeType;
	@Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 40)
	private Reason reasonCode;
	@Enumerated(EnumType.STRING) @Column(name = "weather_reason_code", length = 40)
	private WeatherReason weatherReasonCode;
	@Column(name = "reason_detail", columnDefinition = "TEXT")
	private String reasonDetail;
	@Column(name = "effective_at", nullable = false, updatable = false)
	private LocalDateTime effectiveAt;
	@Column(name = "recorded_at", nullable = false, updatable = false)
	private LocalDateTime recordedAt;
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
	@Column(name = "source_version", nullable = false)
	private long sourceVersion;

	public WorkChange(long assignmentId, long actorId, Type type, Reason reason, WeatherReason weatherReason,
			String detail, LocalDateTime effectiveAt, LocalDateTime now) {
		this.assignmentId = assignmentId;
		this.actorId = actorId;
		changeType = type;
		reasonCode = reason;
		weatherReasonCode = weatherReason;
		reasonDetail = detail;
		this.effectiveAt = effectiveAt;
		recordedAt = now;
		updatedAt = now;
		sourceVersion = 1;
	}

	// 설명·사유 보완만 허용하고 최초 사건의 ID·종류·발생·기록 시각은 유지한다.
	public void revise(Reason reason, WeatherReason weatherReason, String detail, LocalDateTime now) {
		reasonCode = reason;
		weatherReasonCode = weatherReason;
		reasonDetail = detail;
		updatedAt = now;
		sourceVersion = Math.incrementExact(sourceVersion);
	}

	public enum Type { CANCELLED, EARLY_ENDED }
	public enum Reason { WEATHER, FARM_CIRCUMSTANCES, OTHER }
	public enum WeatherReason { RAIN, WIND, HEAT, OTHER }
}

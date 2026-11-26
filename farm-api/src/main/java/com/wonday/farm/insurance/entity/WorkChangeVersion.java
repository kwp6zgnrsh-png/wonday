package com.wonday.farm.insurance.entity;

import java.time.LocalDateTime;

import com.wonday.farm.change.entity.WorkChange;
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
import org.hibernate.annotations.Immutable;

// 같은 변경 버전의 응답이 바뀌지 않도록 원본의 각 버전을 별도 행으로 보관한다.
@Entity
@Table(name = "work_change_version")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkChangeVersion {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "worker_id", nullable = false)
	private long workerId;
	@Column(name = "change_id", nullable = false)
	private long changeId;
	@Column(name = "source_version", nullable = false)
	private long sourceVersion;
	@Column(name = "sequence_no", nullable = false)
	private long sequenceNo;
	@Column(name = "assignment_id", nullable = false)
	private long assignmentId;
	@Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false, length = 32)
	private WorkChange.Type changeType;
	@Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, length = 40)
	private WorkChange.Reason reasonCode;
	@Enumerated(EnumType.STRING) @Column(name = "weather_reason_code", length = 40)
	private WorkChange.WeatherReason weatherReasonCode;
	@Column(name = "reason_detail", columnDefinition = "TEXT")
	private String reasonDetail;
	@Column(name = "event_time_zone", nullable = false, length = 64)
	private String eventTimeZone;
	@Column(name = "effective_at", nullable = false)
	private LocalDateTime effectiveAt;
	@Column(name = "recorded_at", nullable = false)
	private LocalDateTime recordedAt;
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	public WorkChangeVersion(long workerId, long sequence, WorkChange change, String timeZone) {
		this.workerId = workerId;
		sequenceNo = sequence;
		changeId = change.getId();
		sourceVersion = change.getSourceVersion();
		assignmentId = change.getAssignmentId();
		changeType = change.getChangeType();
		reasonCode = change.getReasonCode();
		weatherReasonCode = change.getWeatherReasonCode();
		reasonDetail = change.getReasonDetail();
		eventTimeZone = timeZone;
		effectiveAt = change.getEffectiveAt();
		recordedAt = change.getRecordedAt();
		updatedAt = change.getUpdatedAt();
	}
}

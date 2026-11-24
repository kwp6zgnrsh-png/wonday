package com.wonday.insurance.platform;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "platform_work_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PlatformWorkEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "platform_link_id", nullable = false)
	private long platformLinkId;

	@Column(name = "source_change_id", nullable = false)
	private long sourceChangeId;

	@Column(name = "source_version", nullable = false, length = 100)
	private String sourceVersion;

	@Column(name = "platform_assignment_id", nullable = false)
	private long assignmentId;

	@Column(name = "event_type", nullable = false, length = 40)
	private String eventType;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "reason_code", nullable = false, length = 40)
	private String reasonCode;

	@Column(name = "reason_detail", columnDefinition = "TEXT")
	private String reasonDetail;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "source_updated_at", nullable = false)
	private LocalDateTime sourceUpdatedAt;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "received_at", nullable = false)
	private LocalDateTime receivedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "work_snapshot", columnDefinition = "JSON")
	private String workSnapshot;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "source_payload", columnDefinition = "JSON")
	private String sourcePayload;

	PlatformWorkEvent(long linkId, FarmWorkResponse.Change change, String payload,
		String snapshot, LocalDateTime now) {
		platformLinkId = linkId;
		sourceChangeId = change.changeId();
		sourceVersion = change.sourceVersion();
		assignmentId = change.assignmentId();
		eventType = change.changeType().name();
		occurredAt = change.effectiveAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		reasonCode = change.reasonCode().name();
		reasonDetail = change.reasonDetail();
		sourceUpdatedAt = change.updatedAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
		receivedAt = now;
		sourcePayload = payload;
		workSnapshot = snapshot;
	}
}

package com.wonday.insurance.claim;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "claim_case")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ClaimCase {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "contract_id", nullable = false)
	private long contractId;

	@Column(name = "platform_assignment_id", nullable = false)
	private long assignmentId;

	@Column(name = "trigger_event_id", nullable = false)
	private long triggerEventId;

	@Column(name = "opened_via", nullable = false, length = 32)
	private String openedVia;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "work_snapshot", nullable = false, columnDefinition = "JSON")
	private String workSnapshot;

	@Column(nullable = false, length = 40)
	private String status;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "opened_at", nullable = false)
	private LocalDateTime openedAt;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	ClaimCase(long contractId, long assignmentId, long eventId, String snapshot, LocalDateTime now) {
		this.contractId = contractId;
		this.assignmentId = assignmentId;
		this.triggerEventId = eventId;
		this.workSnapshot = snapshot;
		this.openedVia = "AUTO";
		this.status = "REVIEWING";
		this.openedAt = now;
		this.updatedAt = now;
	}
}

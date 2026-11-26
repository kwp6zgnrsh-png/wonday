package com.wonday.insurance.claim;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "claim_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ClaimHistory {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "claim_case_id", nullable = false)
	private long claimCaseId;

	@Column(name = "assessment_id")
	private Long assessmentId;

	@Column(name = "actor_id")
	private Long actorId;

	@Column(nullable = false, length = 48)
	private String action;

	@Column(name = "from_status", length = 40)
	private String fromStatus;

	@Column(name = "to_status", nullable = false, length = 40)
	private String toStatus;

	@Column(columnDefinition = "TEXT")
	private String reason;

	@JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	ClaimHistory(long claimCaseId, LocalDateTime now) {
		this.claimCaseId = claimCaseId;
		this.action = "AUTO_RECEIVED";
		this.toStatus = "REVIEWING";
		this.reason = "기상 사유의 근무 전날·당일 시작 전 취소 자동 접수";
		this.createdAt = now;
	}
}

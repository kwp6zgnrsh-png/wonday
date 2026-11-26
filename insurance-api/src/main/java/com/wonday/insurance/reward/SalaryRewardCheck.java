package com.wonday.insurance.reward;

import com.wonday.insurance.platform.SalaryAccountQueryService.Snapshot;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import static com.wonday.insurance.reward.SalaryRewardRule.*;

@Entity
@Table(name = "salary_reward_check")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class SalaryRewardCheck {

	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@Column(name = "member_id", nullable = false, updatable = false) private long memberId;
	@Column(name = "platform_link_id", nullable = false, updatable = false) private long platformLinkId;
	@Column(name = "platform_worker_id", nullable = false, updatable = false) private long platformWorkerId;
	@Column(name = "consent_version", nullable = false, updatable = false, length = 64) private String consentVersion;
	@Column(name = "request_key", nullable = false, updatable = false, length = 36) private String requestKey;
	@Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 16) private Result result;
	@Enumerated(EnumType.STRING) @Column(name = "reason_code", nullable = false, updatable = false, length = 40) private Reason reasonCode;
	@Column(updatable = false) private Boolean registered;
	@Column(name = "bank_code", updatable = false, length = 3) private String bankCode;
	@Enumerated(EnumType.STRING) @Column(name = "source_error_code", updatable = false, length = 64) private SourceError sourceErrorCode;
	@Column(name = "rule_version", nullable = false, updatable = false, length = 64) private String ruleVersion;
	@Column(name = "checked_at", nullable = false, updatable = false) private LocalDateTime checkedAt;
	@Column(name = "recorded_at", nullable = false, updatable = false) private LocalDateTime recordedAt;

	SalaryRewardCheck(long memberId, String requestKey, Snapshot source, Decision decision,
			LocalDateTime checkedAt, LocalDateTime recordedAt) {
		this.memberId = memberId;
		this.requestKey = requestKey;
		this.platformLinkId = source.linkId();
		this.platformWorkerId = source.workerId();
		this.consentVersion = source.consentVersion();
		this.result = decision.result();
		this.reasonCode = decision.reason();
		this.registered = decision.registered();
		this.bankCode = decision.bankCode();
		this.sourceErrorCode = decision.error();
		this.ruleVersion = VERSION;
		this.checkedAt = checkedAt;
		this.recordedAt = recordedAt;
	}
}

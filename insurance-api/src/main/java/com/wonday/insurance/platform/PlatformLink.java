package com.wonday.insurance.platform;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "platform_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PlatformLink {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private long memberId;

	@Column(name = "platform_worker_id")
	private Long workerId;

	@Column(name = "consent_version", nullable = false, length = 64)
	private String consentVersion;

	@Column(name = "consented_at", nullable = false)
	private LocalDateTime consentedAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	PlatformLink(long memberId, long workerId, String consentVersion, LocalDateTime consentedAt) {
		this.memberId = memberId;
		this.workerId = workerId;
		this.consentVersion = consentVersion;
		this.consentedAt = consentedAt;
	}

	void revoke(LocalDateTime at) {
		revokedAt = at;
	}
}

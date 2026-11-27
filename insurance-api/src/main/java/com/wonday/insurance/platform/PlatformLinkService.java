package com.wonday.insurance.platform;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlatformLinkService {

	private final PlatformLinkRepository links;
	private final FarmPlatformProperties properties;

	@Transactional(propagation = Propagation.MANDATORY)
	public void link(long memberId, long workerId, LocalDateTime now) {
		PlatformLink existing = links.findByMemberIdAndRevokedAtIsNull(memberId).orElse(null);
		if (existing != null && existing.getWorkerId() != null && existing.getWorkerId() != workerId) {
			throw conflict();
		}
		PlatformLink owner = links.findByWorkerIdAndRevokedAtIsNull(workerId).orElse(null);
		if (owner != null && owner.getMemberId() != memberId) {
			throw conflict();
		}
		if (existing != null && existing.getWorkerId() != null
			&& properties.consentVersion().equals(existing.getConsentVersion())) {
			return;
		}
		if (existing != null) {
			// 이전 동의 이력을 보존하고 활성 연결의 유일성 제약을 먼저 해제한 뒤 새 동의를 저장한다.
			existing.revoke(now);
			links.flush();
		}
		links.saveAndFlush(new PlatformLink(memberId, workerId, properties.consentVersion(), now));
	}

	static PlatformVerificationException conflict() {
		return new PlatformVerificationException(HttpStatus.CONFLICT,
			"PLATFORM_LINK_CONFLICT", "Farm account is already linked");
	}
}

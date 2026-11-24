package com.wonday.insurance.platform;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PlatformLinkService {

	private final PlatformLinkRepository links;
	private final FarmPlatformProperties properties;

	@Transactional(propagation = Propagation.MANDATORY)
	public void link(long memberId, long workerId, LocalDateTime now) {
		PlatformLink existing = links.findByMemberIdAndRevokedAtIsNull(memberId).orElse(null);
		if (existing != null && existing.getWorkerId() != null && existing.getWorkerId() != workerId) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Member is linked to another farm worker");
		}
		PlatformLink owner = links.findByWorkerIdAndRevokedAtIsNull(workerId).orElse(null);
		if (owner != null && owner.getMemberId() != memberId) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Farm worker is already linked");
		}
		if (existing != null && existing.getWorkerId() != null
			&& properties.consentVersion().equals(existing.getConsentVersion())) {
			return;
		}
		if (existing != null) {
			existing.revoke(now);
			links.flush();
		}
		links.saveAndFlush(new PlatformLink(memberId, workerId, properties.consentVersion(), now));
	}
}

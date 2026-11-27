package com.wonday.insurance.platform;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

// 사용자에게 필요한 연결·동의 상태만 반환한다. 외부 근로자 ID와 식별정보는 노출하지 않는다.
record PlatformLinkStatus(boolean linked, boolean consentRequired, String requiredConsentVersion,
		String consentVersion, OffsetDateTime consentedAt) {

	static PlatformLinkStatus from(PlatformLink link, String requiredVersion) {
		boolean linked = link != null && link.getRevokedAt() == null && link.getWorkerId() != null
			&& link.getWorkerId() > 0 && link.getConsentVersion() != null && !link.getConsentVersion().isBlank();
		return new PlatformLinkStatus(linked, !linked || !requiredVersion.equals(link.getConsentVersion()), requiredVersion,
			linked ? link.getConsentVersion() : null, linked ? link.getConsentedAt().atOffset(ZoneOffset.UTC) : null);
	}
}

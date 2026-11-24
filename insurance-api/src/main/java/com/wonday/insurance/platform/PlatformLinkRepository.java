package com.wonday.insurance.platform;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformLinkRepository extends JpaRepository<PlatformLink, Long> {
	Optional<PlatformLink> findByMemberIdAndRevokedAtIsNull(long memberId);
	Optional<PlatformLink> findByWorkerIdAndRevokedAtIsNull(long workerId);
}

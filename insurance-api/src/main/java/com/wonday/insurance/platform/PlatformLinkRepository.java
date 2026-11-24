package com.wonday.insurance.platform;

import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PlatformLinkRepository extends JpaRepository<PlatformLink, Long> {
	@Query("select l.id from PlatformLink l where l.revokedAt is null and l.workerId is not null order by l.id")
	List<Long> findPollingLinkIds();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from PlatformLink l where l.id = :id and l.revokedAt is null and l.workerId is not null")
	Optional<PlatformLink> lockPollingLink(long id);

	Optional<PlatformLink> findByMemberIdAndRevokedAtIsNull(long memberId);
	Optional<PlatformLink> findByWorkerIdAndRevokedAtIsNull(long workerId);
}

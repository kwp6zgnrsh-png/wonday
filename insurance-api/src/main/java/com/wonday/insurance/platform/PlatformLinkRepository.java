package com.wonday.insurance.platform;

import java.util.Optional;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import com.wonday.insurance.member.MemberRole;

interface PlatformLinkRepository extends JpaRepository<PlatformLink, Long> {
	// 엔티티를 캐시에 남기지 않고 매 조회 시점의 연결·동의·현재 회원 역할만 읽는다.
	@Transactional(readOnly = true)
	@Query("""
		select l.id as linkId, l.workerId as workerId, l.consentVersion as consentVersion, m.role as memberRole
		from PlatformLink l, Member m
		where l.memberId = :memberId and m.id = l.memberId and l.revokedAt is null and l.workerId > 0
		""")
	Optional<SalaryLink> findSalaryLink(@Param("memberId") long memberId);

	interface SalaryLink {
		Long getLinkId();
		Long getWorkerId();
		String getConsentVersion();
		MemberRole getMemberRole();
	}

	@Query("select l.id from PlatformLink l where l.revokedAt is null and l.workerId is not null order by l.id")
	List<Long> findPollingLinkIds();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from PlatformLink l where l.id = :id and l.revokedAt is null and l.workerId is not null")
	Optional<PlatformLink> lockPollingLink(long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select l from PlatformLink l
		where l.memberId = :memberId and l.workerId = :workerId and l.revokedAt is null
		""")
	Optional<PlatformLink> lockForClaimIntake(long memberId, long workerId);

	Optional<PlatformLink> findByMemberIdAndRevokedAtIsNull(long memberId);
	Optional<PlatformLink> findByWorkerIdAndRevokedAtIsNull(long workerId);
}

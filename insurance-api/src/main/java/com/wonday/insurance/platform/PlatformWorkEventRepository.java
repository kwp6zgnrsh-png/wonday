package com.wonday.insurance.platform;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface PlatformWorkEventRepository extends JpaRepository<PlatformWorkEvent, Long> {
	Optional<PlatformWorkEvent> findBySourceChangeIdAndSourceVersion(long changeId, String sourceVersion);

	@Query("""
		select e.id from PlatformWorkEvent e
		where e.intakeProcessedAt is null and e.intakeNextAttemptAt <= :now
		order by e.intakeNextAttemptAt, e.id
		""")
	List<Long> findPendingIntake(LocalDateTime now, Pageable page);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select e from PlatformWorkEvent e
		where e.id = :id and e.intakeProcessedAt is null and e.intakeNextAttemptAt <= :now
		""")
	Optional<PlatformWorkEvent> lockPendingIntake(long id, LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from PlatformWorkEvent e where e.id = :id")
	Optional<PlatformWorkEvent> lockForIntakeRetry(long id);
}

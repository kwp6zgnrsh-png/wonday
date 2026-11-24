package com.wonday.insurance.platform;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlatformWorkEventRepository extends JpaRepository<PlatformWorkEvent, Long> {
	Optional<PlatformWorkEvent> findBySourceChangeIdAndSourceVersion(long changeId, String sourceVersion);
}

package com.wonday.insurance.weather;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface WeatherCollectionAttemptRepository extends JpaRepository<WeatherCollectionAttempt, Long> {
	Optional<WeatherCollectionAttempt> findByRequestedByMemberIdAndRequestKey(long memberId, String key);
	Optional<WeatherCollectionAttempt> findByJobIdAndAttemptNumber(long jobId, int number);
	@Query("select a from WeatherCollectionAttempt a where a.jobId=:jobId and (:beforeId is null or a.id<:beforeId) order by a.id desc")
	List<WeatherCollectionAttempt> history(@Param("jobId") long jobId, @Param("beforeId") Long beforeId, Pageable page);
}

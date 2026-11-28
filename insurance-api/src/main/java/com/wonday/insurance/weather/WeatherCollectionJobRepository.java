package com.wonday.insurance.weather;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface WeatherCollectionJobRepository extends JpaRepository<WeatherCollectionJob, Long> {
	@Modifying
	@Query(value = """
		INSERT INTO weather_collection_job (station_code, observation_date, state, attempt_count, lease_expires_at, created_at, updated_at)
		VALUES (:code, :date, 'RUNNING', 0, :now, :now, :now)
		ON DUPLICATE KEY UPDATE id = id
		""", nativeQuery = true)
	void ensureTarget(@Param("code") String code, @Param("date") LocalDate date, @Param("now") LocalDateTime now);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from WeatherCollectionJob j where j.stationCode=:code and j.observationDate=:date")
	Optional<WeatherCollectionJob> lockTarget(@Param("code") String code, @Param("date") LocalDate date);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from WeatherCollectionJob j where j.id=:id")
	Optional<WeatherCollectionJob> lockJob(@Param("id") long id);

	@Query("""
		select j from WeatherCollectionJob j where (:state is null or j.state=:state)
		and (:code is null or j.stationCode=:code) and (:date is null or j.observationDate=:date)
		and (:beforeId is null or j.id<:beforeId) order by j.id desc
		""")
	List<WeatherCollectionJob> history(@Param("state") WeatherCollectionJob.State state, @Param("code") String code,
		@Param("date") LocalDate date, @Param("beforeId") Long beforeId, Pageable page);
}

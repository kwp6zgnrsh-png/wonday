package com.wonday.farm.insurance.repository;

import java.util.Optional;

import com.wonday.farm.insurance.entity.ChangeStream;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChangeStreamRepository extends JpaRepository<ChangeStream, Long> {
	@Modifying
	@Query(value = """
		insert into insurance_change_stream (worker_id, last_sequence) values (:workerId, 0)
		on duplicate key update worker_id = worker_id
		""", nativeQuery = true)
	void ensureExists(@Param("workerId") long workerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select stream from ChangeStream stream where stream.workerId = :workerId")
	Optional<ChangeStream> lockWorker(@Param("workerId") long workerId);
}

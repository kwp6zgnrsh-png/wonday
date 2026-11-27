package com.wonday.farm.application.repository;

import java.util.Optional;
import com.wonday.farm.application.entity.ApplicationRequestLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ApplicationRequestLockRepository extends Repository<ApplicationRequestLock, Long> {
	@Modifying
	@Query(value = """
		insert into application_request_lock (worker_id) values (:workerId)
		on duplicate key update worker_id = worker_id
		""", nativeQuery = true)
	void ensureExists(@Param("workerId") long workerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select requestLock from ApplicationRequestLock requestLock where requestLock.workerId = :workerId")
	Optional<ApplicationRequestLock> lockWorker(@Param("workerId") long workerId);
}
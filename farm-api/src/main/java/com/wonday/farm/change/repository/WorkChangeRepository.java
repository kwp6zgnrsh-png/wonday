package com.wonday.farm.change.repository;

import java.util.Optional;

import com.wonday.farm.change.entity.WorkChange;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkChangeRepository extends JpaRepository<WorkChange, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select change from WorkChange change where change.assignmentId = :assignmentId")
	Optional<WorkChange> findForUpdate(@Param("assignmentId") long assignmentId);
}

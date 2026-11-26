package com.wonday.farm.insurance.repository;

import java.util.Optional;

import com.wonday.farm.insurance.entity.ChangeCursor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeCursorRepository extends JpaRepository<ChangeCursor, String> {
	Optional<ChangeCursor> findByWorkerIdAndAfterSequence(long workerId, long afterSequence);
}

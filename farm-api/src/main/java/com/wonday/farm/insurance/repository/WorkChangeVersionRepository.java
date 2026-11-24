package com.wonday.farm.insurance.repository;

import java.util.List;

import com.wonday.farm.insurance.entity.WorkChangeVersion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkChangeVersionRepository extends JpaRepository<WorkChangeVersion, Long> {
	List<WorkChangeVersion> findByWorkerIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
		long workerId, long sequenceNo, Pageable page);
}

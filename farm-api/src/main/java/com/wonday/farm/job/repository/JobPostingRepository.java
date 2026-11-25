package com.wonday.farm.job.repository;

import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.entity.JobPosting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
	List<JobPosting> findByEmployer_IdOrderByIdDesc(long employerId);

	// 공통 조건 수정·선정도 이후 같은 공고 잠금부터 획득하도록 연결한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select posting from JobPosting posting where posting.id = :postingId and posting.employer.id = :employerId")
	Optional<JobPosting> findOwnedForUpdate(@Param("postingId") long postingId, @Param("employerId") long employerId);
}

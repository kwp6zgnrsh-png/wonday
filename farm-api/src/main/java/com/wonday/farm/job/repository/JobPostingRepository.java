package com.wonday.farm.job.repository;

import java.util.List;
import java.util.Optional;

import com.wonday.farm.job.entity.JobPosting;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {
	List<JobPosting> findByEmployer_IdOrderByIdDesc(long employerId);

	// 후보를 ID 역순으로 제한 조회한다. 정확한 시작 판정은 저장 시간대를 해석하는 서비스에서 수행한다.
	@Query("""
		select posting from JobPosting posting
		where (:beforeId is null or posting.id < :beforeId)
		  and exists (select schedule.id from WorkSchedule schedule
		      where schedule.jobPosting = posting and schedule.status = :status
		        and schedule.plannedStartAt > :earliestStart)
		order by posting.id desc
		""")
	List<JobPosting> findRecruitmentCandidates(@Param("beforeId") Long beforeId,
		@Param("status") com.wonday.farm.job.entity.WorkSchedule.Status status,
		@Param("earliestStart") java.time.LocalDateTime earliestStart, Pageable pageable);

	// 지원·일정 수정·모집 마감·선정은 같은 공고 → 일정 잠금 순서를 사용한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select posting from JobPosting posting where posting.id = :postingId")
	Optional<JobPosting> findByIdForUpdate(@Param("postingId") long postingId);

	// 공통 조건 수정·선정도 이후 같은 공고 잠금부터 획득하도록 연결한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select posting from JobPosting posting where posting.id = :postingId and posting.employer.id = :employerId")
	Optional<JobPosting> findOwnedForUpdate(@Param("postingId") long postingId, @Param("employerId") long employerId);
}

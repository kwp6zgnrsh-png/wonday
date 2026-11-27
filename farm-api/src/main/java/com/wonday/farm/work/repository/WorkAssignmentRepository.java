package com.wonday.farm.work.repository;

import java.util.Optional;

import com.wonday.farm.work.entity.WorkAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WorkAssignmentRepository extends Repository<WorkAssignment, Long> {
	// 완료와 취소·조기 종료가 같은 확정 근무 행을 먼저 잠그도록 한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select assignment from WorkAssignment assignment where assignment.id = :assignmentId")
	Optional<WorkAssignment> findByIdForUpdate(@Param("assignmentId") long assignmentId);

	@Query(value = """
		select assignment.* from work_assignment assignment
		join application app on app.id = assignment.application_id
		where assignment.id = :assignmentId and app.worker_id = :workerId
		""", nativeQuery = true)
	Optional<WorkAssignment> findOwned(@Param("workerId") long workerId, @Param("assignmentId") long assignmentId);

	// 기록 처리에서는 근로자와 고용주를 저장된 연결 관계로 확인한다.
	@Query(value = """
		select app.worker_id as workerId, posting.employer_id as employerId
		from work_assignment assignment
		join application app on app.id = assignment.application_id
		join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		where assignment.id = :assignmentId
		""", nativeQuery = true)
	Optional<Ownership> findOwnership(@Param("assignmentId") long assignmentId);

	interface Ownership {
		long getWorkerId();
		long getEmployerId();
	}
}

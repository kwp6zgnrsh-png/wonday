package com.wonday.farm.application.repository;

import java.util.Optional;
import com.wonday.farm.application.entity.JobApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select app from JobApplication app
		where app.workerId = :workerId and app.workScheduleId = :scheduleId and app.current = true
		""")
	Optional<JobApplication> findCurrentForUpdate(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId);

	@Query("""
		select coalesce(max(app.applicationRound), 0) from JobApplication app
		where app.workerId = :workerId and app.workScheduleId = :scheduleId
		""")
	int maxRound(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId);

	// 과거 회차까지 검사한다. 시작 전 취소만 재지원을 허용하고 유효한 확정 근무는 차단한다.
	@Query(value = """
		select assignment.id from work_assignment assignment
		join application app on app.id = assignment.application_id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where app.worker_id = :workerId and app.work_schedule_id = :scheduleId
		  and (change_record.id is null or change_record.change_type <> 'CANCELLED'
		       or assignment.completed_at_utc is not null or assignment.completed_by is not null)
		order by assignment.id
		limit 1
		""", nativeQuery = true)
	Optional<Long> findBlockingAssignment(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId);

	@Query(value = """
		select assignment.id from work_assignment assignment
		join work_change change_record on change_record.assignment_id = assignment.id
		where assignment.application_id = :applicationId and change_record.change_type = 'CANCELLED'
		  and assignment.completed_at_utc is null and assignment.completed_by is null
		""", nativeQuery = true)
	Optional<Long> findCancelledAssignment(@Param("applicationId") long applicationId);
}
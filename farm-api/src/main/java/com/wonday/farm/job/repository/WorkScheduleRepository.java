package com.wonday.farm.job.repository;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.time.LocalDate;

import com.wonday.farm.job.entity.WorkSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {
	// 근로자 조회용이다. 잠금을 걸거나 다른 고용주의 비공개 회원 정보를 읽지 않는다.
	@Query("""
		select schedule from WorkSchedule schedule
		join fetch schedule.jobPosting posting
		where posting.id in :postingIds
		order by posting.id desc, schedule.workDate, schedule.id
		""")
	List<WorkSchedule> findForWorkerPostings(@Param("postingIds") Collection<Long> postingIds);

	@Query(value = """
		select schedule.id as workScheduleId,
		       count(distinct case when change_record.id is null or change_record.change_type <> 'CANCELLED'
		             then assignment.id end) as confirmedCount
		from work_schedule schedule
		left join application app on app.work_schedule_id = schedule.id
		left join work_assignment assignment on assignment.application_id = app.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where schedule.id in (:scheduleIds)
		group by schedule.id
		""", nativeQuery = true)
	List<OccupyingCount> countWorkerScheduleAssignments(@Param("scheduleIds") Collection<Long> scheduleIds);

	// 현재 회차만 읽어 과거 미선정·취소 이력이 최신 지원 상태를 덮지 않게 한다.
	// 다른 근로자의 이름·전화번호·식별정보는 조회하지 않는다.
	@Query(value = """
		select app.work_schedule_id as workScheduleId, app.status as applicationStatus,
		       assignment.id as assignmentId, change_record.change_type as changeType
		from application app
		left join work_assignment assignment on assignment.application_id = app.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where app.worker_id = :workerId and app.work_schedule_id in (:scheduleIds)
		  and app.is_current = 1
		""", nativeQuery = true)
	List<WorkerApplication> findWorkerApplications(@Param("workerId") long workerId,
		@Param("scheduleIds") Collection<Long> scheduleIds);

	interface OccupyingCount {
		Long getWorkScheduleId();
		long getConfirmedCount();
	}

	interface WorkerApplication {
		Long getWorkScheduleId();
		String getApplicationStatus();
		Long getAssignmentId();
		String getChangeType();
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<WorkSchedule> findByIdAndJobPosting_Id(long id, long jobPostingId);

	boolean existsByJobPosting_IdAndWorkDateAndIdNot(long jobPostingId, LocalDate workDate, long id);

	// CANCELLED는 시작 전 취소로 기록된 근무다. 조기 종료는 자리를 비운 것으로 취급하지 않는다.
	@Query(value = """
		select count(*) from work_assignment assignment
		join application app on app.id = assignment.application_id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where app.work_schedule_id = :scheduleId
		  and (change_record.id is null or change_record.change_type <> 'CANCELLED')
		""", nativeQuery = true)
	long countOccupyingAssignments(@Param("scheduleId") long scheduleId);

	// 소유자 조건은 DB 조회에 적용하고, 공고도 함께 읽어 일정별 추가 조회를 방지한다.
	@Query("""
		select schedule from WorkSchedule schedule
		join fetch schedule.jobPosting posting
		where posting.employer.id = :employerId
		order by schedule.workDate, schedule.id
		""")
	List<WorkSchedule> findOwnedSchedules(@Param("employerId") long employerId);

	// 지원 회차 수가 아니라 근로자 수를 센다. 과거 이력도 남기되 같은 근로자는 한 명이다.
	// SELECTED 표시만 있는 지원과 실제 확정 근무 생성 건수를 구분한다.
	@Query(value = """
		select schedule.id as workScheduleId,
		       count(distinct application.worker_id) as applicantCount,
		       count(distinct case when change_record.id is null or change_record.change_type <> 'CANCELLED'
		             then assignment.id end) as confirmedCount
		from work_schedule schedule
		join job_posting posting on posting.id = schedule.job_posting_id
		left join application on application.work_schedule_id = schedule.id
		left join work_assignment assignment on assignment.application_id = application.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where posting.employer_id = :employerId
		group by schedule.id
		""", nativeQuery = true)
	List<ScheduleCounts> countOwnedSchedules(@Param("employerId") long employerId);

	interface ScheduleCounts {
		Long getWorkScheduleId();
		long getApplicantCount();
		long getConfirmedCount();
	}
}

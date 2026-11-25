package com.wonday.farm.job.repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

import com.wonday.farm.job.entity.WorkSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {
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

	// 아직 엔티티가 없는 지원·확정 테이블은 JPA의 읽기 전용 네이티브 집계로 조회한다.
	// SELECTED 표시만 있는 지원과 실제 확정 근무 생성 건수를 구분한다.
	@Query(value = """
		select schedule.id as workScheduleId,
		       count(distinct application.id) as applicantCount,
		       count(distinct assignment.id) as confirmedCount
		from work_schedule schedule
		join job_posting posting on posting.id = schedule.job_posting_id
		left join application on application.work_schedule_id = schedule.id
		left join work_assignment assignment on assignment.application_id = application.id
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

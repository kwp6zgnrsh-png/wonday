package com.wonday.farm.demo.completion;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.work.entity.WorkAssignment;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 근로자·고용주 ID를 화면에서 받지 않고, 세션 소유자의 공고에 연결된 약정만 읽는다.
@Profile("farm-demo")
public interface DemoWorkCompletionRepository extends Repository<WorkAssignment, Long> {
	@Query(value = """
		select assignment.id as assignmentId, posting.id as jobPostingId, posting.title as title,
		       worker.name as workerName, assignment.agreed_work_date as agreedWorkDate
		from work_assignment assignment
		join application app on app.id = assignment.application_id
		join member worker on worker.id = app.worker_id
		join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		where posting.employer_id = :employerId
		order by assignment.id desc
		limit 100
		""", nativeQuery = true)
	List<Option> findForEmployer(@Param("employerId") long employerId);

	@Query(value = """
		select assignment.id as assignmentId, posting.id as jobPostingId, posting.title as title,
		       worker.name as workerName, assignment.agreed_work_date as agreedWorkDate,
		       assignment.agreed_time_zone as agreedTimeZone, assignment.agreed_end_at as agreedEndAt,
		       assignment.completed_at_utc as completedAtUtc, change_record.change_type as changeType
		from work_assignment assignment
		join application app on app.id = assignment.application_id
		join member worker on worker.id = app.worker_id
		join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where posting.employer_id = :employerId and assignment.id = :assignmentId
		""", nativeQuery = true)
	Optional<Detail> findDetailForEmployer(@Param("employerId") long employerId, @Param("assignmentId") long assignmentId);

	interface Option {
		long getAssignmentId();
		long getJobPostingId();
		String getTitle();
		String getWorkerName();
		LocalDate getAgreedWorkDate();
	}

	interface Detail extends Option {
		String getAgreedTimeZone();
		LocalDateTime getAgreedEndAt();
		LocalDateTime getCompletedAtUtc();
		String getChangeType();
	}
}
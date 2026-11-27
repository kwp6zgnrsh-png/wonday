package com.wonday.farm.demo.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.wonday.farm.application.entity.JobApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// demo 전용이다. 내 지원 이력만 읽고, 준비 기능은 새로 만든 시연 일정에만 과거 미선정 행을 넣는다.
@Profile("farm-demo")
public interface DemoJobApplicationRepository extends Repository<JobApplication, Long> {
	@Query(value = """
		select app.id as applicationId, app.work_schedule_id as workScheduleId,
		       app.application_round as applicationRound, app.status as status,
		       app.is_current as currentFlag, app.applied_terms_version as appliedTermsVersion,
		       assignment.id as assignmentId, change_record.change_type as changeType
		from application app
		join work_schedule schedule on schedule.id = app.work_schedule_id
		left join work_assignment assignment on assignment.application_id = app.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where app.worker_id = :workerId and schedule.job_posting_id = :postingId
		order by app.id desc
		limit 500
		""", nativeQuery = true)
	List<History> findOwnHistory(@Param("workerId") long workerId, @Param("postingId") long postingId);

	@Query(value = """
		select id from job_posting
		where employer_id = :employerId and title = :marker
		order by id
		limit 1
		""", nativeQuery = true)
	Optional<Long> findFixturePosting(@Param("employerId") long employerId, @Param("marker") String marker);

	// 기존 지원을 변경하는 UPDATE는 제공하지 않는다. 새 시연 일정의 1회차만 JPA로 삽입한다.
	@Modifying
	@Query(value = """
		insert into application (work_schedule_id, worker_id, status, applied_at, applied_at_utc,
		                         application_round, applied_terms_version, is_current)
		values (:scheduleId, :workerId, 'NOT_SELECTED', :nowUtc, :nowUtc, 1, 1, 1)
		""", nativeQuery = true)
	int insertNotSelectedFixture(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId,
		@Param("nowUtc") LocalDateTime nowUtc);

	interface History {
		long getApplicationId();
		long getWorkScheduleId();
		int getApplicationRound();
		String getStatus();
		int getCurrentFlag();
		Integer getAppliedTermsVersion();
		Long getAssignmentId();
		String getChangeType();
	}
}
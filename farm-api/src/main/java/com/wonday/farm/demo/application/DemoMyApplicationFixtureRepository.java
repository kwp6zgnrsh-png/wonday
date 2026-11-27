package com.wonday.farm.demo.application;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// JPA 저장소를 통한 demo 전용 INSERT다. 기존 지원/약정에 대한 UPDATE·DELETE는 제공하지 않는다.
@Profile("farm-demo")
public interface DemoMyApplicationFixtureRepository extends Repository<JobApplication, Long> {
	@Modifying
	@Query(value = """
		insert into application (work_schedule_id, worker_id, status, applied_at, applied_at_utc,
		 application_round, applied_terms_version, is_current)
		select schedule.id, :workerId, :status, :at, :at, :round, 1, :currentFlag
		from work_schedule schedule join job_posting posting on posting.id = schedule.job_posting_id
		where schedule.id = :scheduleId and posting.id = :postingId
		 and posting.employer_id = :employerId and posting.title = :marker
		""", nativeQuery = true)
	int insertApplication(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId,
		@Param("postingId") long postingId, @Param("employerId") long employerId, @Param("marker") String marker,
		@Param("status") String status, @Param("round") int round, @Param("currentFlag") int currentFlag,
		@Param("at") LocalDateTime at);

	@Query(value = """
		select id from application where worker_id = :workerId
		 and work_schedule_id = :scheduleId and application_round = :round
		""", nativeQuery = true)
	Optional<Long> findApplicationId(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId,
		@Param("round") int round);

	@Modifying
	@Query(value = """
		insert into work_assignment (application_id, confirmed_at, confirmed_at_utc,
		 agreed_work_date, agreed_time_zone, agreed_work_location, agreed_latitude, agreed_longitude,
		 agreed_crop_code, agreed_work_type_code, agreed_start_at, agreed_end_at,
		 agreed_break_start_at, agreed_break_end_at, agreed_daily_wage)
		select app.id, :confirmedAt, :confirmedAt, schedule.work_date, posting.time_zone, posting.work_location,
		 posting.latitude, posting.longitude, posting.crop_code, posting.work_type_code,
		 schedule.planned_start_at, schedule.planned_end_at, schedule.break_start_at, schedule.break_end_at,
		 posting.default_daily_wage
		from application app join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		where app.id = :applicationId and app.worker_id = :workerId and posting.id = :postingId
		 and posting.employer_id = :employerId and posting.title = :marker and app.status = 'SELECTED'
		""", nativeQuery = true)
	int insertAssignment(@Param("applicationId") long applicationId, @Param("workerId") long workerId,
		@Param("postingId") long postingId, @Param("employerId") long employerId, @Param("marker") String marker,
		@Param("confirmedAt") LocalDateTime confirmedAt);

	@Query(value = "select id from work_assignment where application_id = :applicationId", nativeQuery = true)
	Optional<Long> findAssignmentId(@Param("applicationId") long applicationId);

	@Query(value = """
		select app.id as applicationId, app.work_schedule_id as workScheduleId,
		 app.application_round as applicationRound, cast(app.is_current as signed) as currentFlag,
		 app.applied_terms_version as appliedTermsVersion, schedule.terms_version as latestTermsVersion,
		 schedule.work_date as workDate, assignment.id as assignmentId,
		""" + MyApplicationQueryRepository.DISPLAY_STATUS + """
		 as displayStatus
		from application app
		join work_schedule schedule on schedule.id = app.work_schedule_id
		left join work_assignment assignment on assignment.application_id = app.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		where app.worker_id = :workerId and schedule.job_posting_id = :postingId
		order by schedule.id, app.application_round desc
		""", nativeQuery = true)
	List<ScenarioRow> findScenarios(@Param("workerId") long workerId, @Param("postingId") long postingId);

	interface ScenarioRow {
		long getApplicationId(); long getWorkScheduleId(); int getApplicationRound(); int getCurrentFlag();
		Integer getAppliedTermsVersion(); int getLatestTermsVersion(); LocalDate getWorkDate();
		Long getAssignmentId(); String getDisplayStatus();
	}
}

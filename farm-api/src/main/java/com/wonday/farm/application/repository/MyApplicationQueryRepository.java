package com.wonday.farm.application.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.wonday.farm.application.entity.JobApplication;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 단일 JOIN 조회로 지원·일정·약정·변경·조건 이력을 읽는다. 어떤 경로도 worker_id 조건을 생략하지 않는다.
public interface MyApplicationQueryRepository extends Repository<JobApplication, Long> {
	String DISPLAY_STATUS = """
		case
		 when assignment.id is not null and change_record.change_type = 'CANCELLED' then 'CANCELLED'
		 when assignment.id is not null and change_record.change_type = 'EARLY_ENDED' then 'EARLY_ENDED'
		 when assignment.id is not null and assignment.completed_at_utc is not null then 'COMPLETED'
		 when assignment.id is not null then 'CONFIRMED'
		 when schedule.status = 'REMOVED' then 'CANCELLED'
		 when app.status = 'PENDING' then 'PENDING'
		 when app.status = 'NOT_SELECTED' then 'NOT_SELECTED'
		 else 'UNKNOWN'
		end
		""";
	String SELECT_ROW = """
		select app.id as applicationId, app.work_schedule_id as workScheduleId,
		 app.application_round as applicationRound, cast(app.is_current as signed) as currentFlag,
		 app.status as applicationStatus, app.applied_at as legacyAppliedAt, app.applied_at_utc as appliedAtUtc,
		 coalesce(app.applied_at_utc, app.applied_at) as sortAppliedAt,
		 app.applied_terms_version as appliedTermsVersion,
         acknowledgement.id as acknowledgementId, acknowledgement.terms_version as acknowledgedTermsVersion,
         acknowledgement.acknowledged_at_utc as acknowledgedAtUtc,
		 posting.id as jobPostingId, posting.title as title, posting.description as description,
		 posting.time_zone as timeZone, posting.work_location as workLocation,
		 posting.latitude as latitude, posting.longitude as longitude,
		 posting.crop_code as cropCode, posting.work_type_code as workTypeCode,
		 posting.default_headcount as headcount, posting.default_daily_wage as dailyWage,
		 schedule.work_date as workDate, schedule.planned_start_at as plannedStartAt,
		 schedule.planned_end_at as plannedEndAt, schedule.break_start_at as breakStartAt,
		 schedule.break_end_at as breakEndAt, schedule.terms_version as latestTermsVersion,
		 schedule.status as scheduleStatus,
		 revision.id as revisionId, revision.work_date as revisionWorkDate,
		 revision.time_zone as revisionTimeZone, revision.work_location as revisionWorkLocation,
		 revision.crop_code as revisionCropCode, revision.work_type_code as revisionWorkTypeCode,
		 revision.headcount as revisionHeadcount, revision.daily_wage as revisionDailyWage,
		 revision.planned_start_at as revisionStartAt, revision.planned_end_at as revisionEndAt,
		 revision.break_start_at as revisionBreakStartAt, revision.break_end_at as revisionBreakEndAt,
		 assignment.id as assignmentId, assignment.confirmed_at_utc as confirmedAtUtc,
		 assignment.completed_at_utc as completedAtUtc, assignment.agreed_work_date as agreedWorkDate,
		 assignment.agreed_time_zone as agreedTimeZone, assignment.agreed_work_location as agreedWorkLocation,
		 assignment.agreed_latitude as agreedLatitude, assignment.agreed_longitude as agreedLongitude,
		 assignment.agreed_crop_code as agreedCropCode, assignment.agreed_work_type_code as agreedWorkTypeCode,
		 assignment.agreed_daily_wage as agreedDailyWage, assignment.agreed_start_at as agreedStartAt,
		 assignment.agreed_end_at as agreedEndAt, assignment.agreed_break_start_at as agreedBreakStartAt,
		 assignment.agreed_break_end_at as agreedBreakEndAt,
		 change_record.change_type as changeType, change_record.reason_code as reasonCode,
		 change_record.weather_reason_code as weatherReasonCode, change_record.reason_detail as reasonDetail,
		 change_record.effective_at as effectiveAtUtc, change_record.recorded_at as recordedAtUtc,
		 change_record.updated_at as updatedAtUtc,
		""" + DISPLAY_STATUS + " as displayStatus ";
	String FROM_ROW = """
		from application app
		join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
        left join application_condition_acknowledgement acknowledgement on acknowledgement.application_id = app.id
         and acknowledgement.terms_version = (select max(saved.terms_version)
             from application_condition_acknowledgement saved where saved.application_id = app.id)
		left join work_assignment assignment on assignment.application_id = app.id
		left join work_change change_record on change_record.assignment_id = assignment.id
		left join work_schedule_revision revision on revision.work_schedule_id = schedule.id
		 and revision.terms_version = app.applied_terms_version
		""";

	@Query(value = SELECT_ROW + FROM_ROW + """
		where app.worker_id = :workerId and app.is_current = 1
		 and (:filter = 'ALL' or (""" + DISPLAY_STATUS + """
		 ) = :filter)
		 and (:beforeId is null or coalesce(app.applied_at_utc, app.applied_at) < :beforeAt
		  or (coalesce(app.applied_at_utc, app.applied_at) = :beforeAt and app.id < :beforeId))
		order by coalesce(app.applied_at_utc, app.applied_at) desc, app.id desc
		limit :fetchLimit
		""", nativeQuery = true)
	List<Row> findCurrent(@Param("workerId") long workerId, @Param("filter") String filter,
		@Param("beforeAt") LocalDateTime beforeAt, @Param("beforeId") Long beforeId, @Param("fetchLimit") int fetchLimit);

	@Query(value = SELECT_ROW + FROM_ROW + """
		where app.worker_id = :workerId and app.id = :applicationId
		""", nativeQuery = true)
	Optional<Row> findOwned(@Param("workerId") long workerId, @Param("applicationId") long applicationId);

	@Query(value = SELECT_ROW + FROM_ROW + """
		where app.worker_id = :workerId and app.work_schedule_id = :scheduleId and app.is_current = 0
		 and (:beforeId is null or app.application_round < :beforeRound
		  or (app.application_round = :beforeRound and app.id < :beforeId))
		order by app.application_round desc, app.id desc
		limit :fetchLimit
		""", nativeQuery = true)
	List<Row> findPast(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId,
		@Param("beforeRound") Integer beforeRound, @Param("beforeId") Long beforeId, @Param("fetchLimit") int fetchLimit);

	@Query(value = """
		select app.id from application app
		where app.worker_id = :workerId and app.work_schedule_id = :scheduleId and app.is_current = 1
		""", nativeQuery = true)
	Optional<Long> findCurrentId(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId);

	interface Row {
		long getApplicationId(); long getWorkScheduleId(); int getApplicationRound(); int getCurrentFlag();
		String getApplicationStatus(); LocalDateTime getLegacyAppliedAt(); LocalDateTime getAppliedAtUtc();
		LocalDateTime getSortAppliedAt(); Long getAcknowledgementId(); Integer getAcknowledgedTermsVersion(); LocalDateTime getAcknowledgedAtUtc();
        Integer getAppliedTermsVersion(); long getJobPostingId();
		String getTitle(); String getDescription(); String getTimeZone(); String getWorkLocation();
		BigDecimal getLatitude(); BigDecimal getLongitude(); String getCropCode(); String getWorkTypeCode();
		int getHeadcount(); long getDailyWage(); LocalDate getWorkDate(); LocalDateTime getPlannedStartAt();
		LocalDateTime getPlannedEndAt(); LocalDateTime getBreakStartAt(); LocalDateTime getBreakEndAt();
		int getLatestTermsVersion(); String getScheduleStatus();
		Long getRevisionId(); LocalDate getRevisionWorkDate(); String getRevisionTimeZone();
		String getRevisionWorkLocation(); String getRevisionCropCode(); String getRevisionWorkTypeCode();
		Integer getRevisionHeadcount(); Long getRevisionDailyWage(); LocalDateTime getRevisionStartAt();
		LocalDateTime getRevisionEndAt(); LocalDateTime getRevisionBreakStartAt(); LocalDateTime getRevisionBreakEndAt();
		Long getAssignmentId(); LocalDateTime getConfirmedAtUtc(); LocalDateTime getCompletedAtUtc();
		LocalDate getAgreedWorkDate(); String getAgreedTimeZone(); String getAgreedWorkLocation();
		BigDecimal getAgreedLatitude(); BigDecimal getAgreedLongitude(); String getAgreedCropCode();
		String getAgreedWorkTypeCode(); Long getAgreedDailyWage(); LocalDateTime getAgreedStartAt();
		LocalDateTime getAgreedEndAt(); LocalDateTime getAgreedBreakStartAt(); LocalDateTime getAgreedBreakEndAt();
		String getChangeType(); String getReasonCode(); String getWeatherReasonCode(); String getReasonDetail();
		LocalDateTime getEffectiveAtUtc(); LocalDateTime getRecordedAtUtc(); LocalDateTime getUpdatedAtUtc();
		String getDisplayStatus();
	}
}

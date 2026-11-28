package com.wonday.farm.work.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.MyApplicationQueryRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 전체 번호는 contact 쿼리만 읽는다. 목록·상세 projection에는 마스킹 값만 포함한다.
public interface WorkApplicantRepository extends Repository<JobApplication, Long> {
	String PHONE = "regexp_replace(coalesce(worker.phone, ''), '[-[:space:]]', '')";
	String PHONE_STATE = "case when " + PHONE + " = '' then 'NOT_REGISTERED' when " + PHONE
		+ " regexp '^[0-9]{10,11}$' then 'AVAILABLE' else 'INVALID_FORMAT' end";
	String SELECT = MyApplicationQueryRepository.SELECT_ROW + """
		, app.worker_id as workerId, worker.name as workerName,
		""" + "case when " + PHONE + " regexp '^[0-9]{10,11}$' then concat(left(" + PHONE
		+ ",3), '-', repeat('*', char_length(" + PHONE + ")-7), '-', right(" + PHONE + ",4)) else null end as maskedPhone, "
		+ PHONE_STATE + " as phoneState ";
	String FROM = MyApplicationQueryRepository.FROM_ROW + " join member worker on worker.id = app.worker_id ";
	String SCOPE = " where posting.employer_id = :employerId and posting.id = :postingId and schedule.id = :scheduleId ";

	@Query(value = "select id from job_posting where id = :postingId and employer_id = :employerId", nativeQuery = true)
	Optional<Long> findOwnedPosting(@Param("employerId") long employerId, @Param("postingId") long postingId);

	@Query(value = """
		select schedule.id as workScheduleId, schedule.work_date as workDate, schedule.status as recruitmentStatus,
		 posting.default_headcount as headcount
		from work_schedule schedule join job_posting posting on posting.id = schedule.job_posting_id
		where posting.employer_id = :employerId and posting.id = :postingId and schedule.id = :scheduleId
		""", nativeQuery = true)
	Optional<Schedule> findOwnedSchedule(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId);

	@Query(value = SELECT + FROM + SCOPE + """
		and app.is_current = 1 and (:filter = 'ALL' or app.status = :filter)
		and (:afterId is null or coalesce(app.applied_at_utc, app.applied_at) > :afterAt
		 or (coalesce(app.applied_at_utc, app.applied_at) = :afterAt and app.id > :afterId))
		order by coalesce(app.applied_at_utc, app.applied_at), app.id
		limit :fetchLimit
		""", nativeQuery = true)
	List<Row> findCurrent(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId, @Param("filter") String filter,
		@Param("afterAt") LocalDateTime afterAt, @Param("afterId") Long afterId, @Param("fetchLimit") int fetchLimit);

	@Query(value = SELECT + FROM + SCOPE + " and app.id = :applicationId and app.is_current = 1", nativeQuery = true)
	Optional<Row> findCurrentOwned(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId, @Param("applicationId") long applicationId);

	// 다음 페이지의 앵커가 이후 과거 회차가 되어도 원래 접수 시각 경계를 유지한다.
	@Query(value = SELECT + FROM + SCOPE + " and app.id = :applicationId", nativeQuery = true)
	Optional<Row> findOwnedAnchor(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId, @Param("applicationId") long applicationId);

	@Query(value = """
		select app.id as applicationId, app.worker_id as workerId, app.status as status,
		 cast(app.is_current as signed) as currentFlag, worker.name as workerName,
		 case when app.is_current = 1 and app.status in ('PENDING', 'SELECTED') then worker.phone else null end as phone
		from application app join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		join member worker on worker.id = app.worker_id
		where posting.employer_id = :employerId and posting.id = :postingId
		 and schedule.id = :scheduleId and app.id = :applicationId
		""", nativeQuery = true)
	Optional<Contact> findContact(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId, @Param("applicationId") long applicationId);

	interface Schedule { long getWorkScheduleId(); LocalDate getWorkDate(); String getRecruitmentStatus(); int getHeadcount(); }
	interface Row extends MyApplicationQueryRepository.Row {
		long getWorkerId(); String getWorkerName(); String getMaskedPhone(); String getPhoneState();
	}
	interface Contact {
		long getApplicationId(); long getWorkerId(); String getStatus(); int getCurrentFlag(); String getWorkerName(); String getPhone();
	}
}

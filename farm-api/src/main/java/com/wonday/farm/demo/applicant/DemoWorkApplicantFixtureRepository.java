package com.wonday.farm.demo.applicant;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.wonday.farm.member.Member;
import jakarta.persistence.LockModeType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

@Profile("farm-demo")
public interface DemoWorkApplicantFixtureRepository extends Repository<Member, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :employerId and m.role = 'EMPLOYER'")
	Optional<Member> lockEmployer(@Param("employerId") long employerId);

	@Modifying
	@Query(value = """
		insert into application (work_schedule_id, worker_id, status, applied_at, applied_at_utc,
		 application_round, applied_terms_version, is_current)
		select schedule.id, :workerId, :status, :at, :at, :round, :version, :currentFlag
		from work_schedule schedule join job_posting posting on posting.id = schedule.job_posting_id
		where schedule.id = :scheduleId and posting.id = :postingId
		 and posting.employer_id = :employerId and posting.title = :marker
		""", nativeQuery = true)
	int insertApplication(@Param("workerId") long workerId, @Param("scheduleId") long scheduleId,
		@Param("postingId") long postingId, @Param("employerId") long employerId, @Param("marker") String marker,
		@Param("status") String status, @Param("round") int round, @Param("version") int version,
		@Param("currentFlag") int currentFlag, @Param("at") LocalDateTime at);

	// 이번 준비 작업에서 새로 만든 일정에만 호출한다. 기존 모집의 중단·재개 업무 API가 아니다.
	@Modifying(flushAutomatically = true)
	@Query(value = """
		update work_schedule schedule join job_posting posting on posting.id = schedule.job_posting_id
		set schedule.status = :status, schedule.cancelled_at = case when :status = 'REMOVED' then :now else null end
		where schedule.id = :scheduleId and posting.id = :postingId and posting.employer_id = :employerId
		 and posting.title = :marker and schedule.status = 'OPEN'
		""", nativeQuery = true)
	int setNewScheduleStatus(@Param("employerId") long employerId, @Param("postingId") long postingId,
		@Param("scheduleId") long scheduleId, @Param("marker") String marker, @Param("status") String status,
		@Param("now") LocalDateTime now);

	@Query(value = """
		select schedule.id as workScheduleId, schedule.work_date as workDate, schedule.status as status,
		 schedule.terms_version as termsVersion
		from work_schedule schedule join job_posting posting on posting.id = schedule.job_posting_id
		where posting.id = :postingId and posting.employer_id = :employerId
		order by schedule.id
		""", nativeQuery = true)
	List<DateRow> findDates(@Param("employerId") long employerId, @Param("postingId") long postingId);

	@Query(value = """
		select app.id as applicationId, app.work_schedule_id as workScheduleId, worker.name as name,
		 app.status as status, app.application_round as applicationRound, cast(app.is_current as signed) as currentFlag,
		 app.applied_terms_version as appliedTermsVersion, schedule.terms_version as latestTermsVersion,
		 case when worker.phone is null then 0 else 1 end as phonePresent
		from application app join work_schedule schedule on schedule.id = app.work_schedule_id
		join job_posting posting on posting.id = schedule.job_posting_id
		join member worker on worker.id = app.worker_id
		where posting.id = :postingId and posting.employer_id = :employerId
		order by schedule.id, app.id
		""", nativeQuery = true)
	List<CaseRow> findCases(@Param("employerId") long employerId, @Param("postingId") long postingId);
	interface DateRow { long getWorkScheduleId(); LocalDate getWorkDate(); String getStatus(); int getTermsVersion(); }
	interface CaseRow {
		long getApplicationId(); long getWorkScheduleId(); String getName(); String getStatus(); int getApplicationRound();
		int getCurrentFlag(); Integer getAppliedTermsVersion(); int getLatestTermsVersion(); int getPhonePresent();
	}
}

package com.wonday.farm.demo.selection;

import java.time.LocalDate;
import java.util.List;
import com.wonday.farm.application.entity.JobApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 오늘의 시연 표식과 실제 소유 관계를 함께 검사한다. 다른 사람의 실제 공고는 조회하지 않는다.
@Profile("farm-demo")
public interface DemoWorkSelectionRepository extends Repository<JobApplication, Long> {
    @Query(value = """
        select posting.id as jobPostingId, schedule.id as workScheduleId, app.id as applicationId,
         app.worker_id as workerId, worker.name as workerName, posting.employer_id as employerId,
         employer.name as employerName, schedule.work_date as workDate, schedule.status as recruitmentStatus,
         schedule.terms_version as termsVersion, app.applied_terms_version as appliedTermsVersion,
         app.status as applicationStatus, assignment.id as assignmentId,
         case when change_record.change_type = 'CANCELLED' then 'CANCELLED'
              when change_record.change_type = 'EARLY_ENDED' then 'EARLY_ENDED'
              when assignment.completed_at_utc is not null then 'COMPLETED'
              when assignment.id is not null then 'CONFIRMED' else app.status end as workStatus
        from application app
        join work_schedule schedule on schedule.id = app.work_schedule_id
        join job_posting posting on posting.id = schedule.job_posting_id
        join member worker on worker.id = app.worker_id
        join member employer on employer.id = posting.employer_id
        left join work_assignment assignment on assignment.application_id = app.id
        left join work_change change_record on change_record.assignment_id = assignment.id
        where (posting.employer_id = :actorId or app.worker_id = :actorId) and app.is_current = 1
         and posting.title = concat('[WORK-02 시연·고용주 ', posting.employer_id,
             '·근로자 ', app.worker_id, '·', cast(:today as char), ']')
        order by posting.id desc, schedule.id
        """, nativeQuery = true)
    List<CaseRow> findCases(@Param("actorId") long actorId, @Param("today") LocalDate today);
    interface CaseRow {
        long getJobPostingId(); long getWorkScheduleId(); long getApplicationId(); long getWorkerId();
        String getWorkerName(); long getEmployerId(); String getEmployerName(); LocalDate getWorkDate();
        String getRecruitmentStatus(); int getTermsVersion(); Integer getAppliedTermsVersion();
        String getApplicationStatus(); Long getAssignmentId(); String getWorkStatus();
    }
}

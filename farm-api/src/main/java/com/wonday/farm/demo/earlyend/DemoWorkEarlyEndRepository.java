package com.wonday.farm.demo.earlyend;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import com.wonday.farm.work.entity.WorkAssignment;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// 시연 표식·소유자·대상 근로자를 모두 확인한다. 임의의 실제 근무를 시연 사례로 가져오지 않는다.
@Profile("farm-demo")
public interface DemoWorkEarlyEndRepository extends Repository<WorkAssignment, Long> {
    @Query(value = """
        select assignment.id as assignmentId, schedule.id as workScheduleId,
               schedule.work_date as workDate, assignment.agreed_time_zone as timeZone,
               assignment.agreed_start_at as startAt, assignment.agreed_end_at as endAt,
               assignment.completed_at_utc as completedAt, change_record.change_type as changeType,
               change_record.effective_at as effectiveAt, change_record.reason_code as reasonCode,
               change_record.weather_reason_code as weatherReasonCode, change_record.reason_detail as reasonDetail
        from work_assignment assignment
        join application app on app.id = assignment.application_id
        join work_schedule schedule on schedule.id = app.work_schedule_id
        join job_posting posting on posting.id = schedule.job_posting_id
        left join work_change change_record on change_record.assignment_id = assignment.id
        where posting.id = :postingId and posting.employer_id = :employerId
          and app.worker_id = :workerId and posting.title = :marker
        order by schedule.id
        """, nativeQuery = true)
    List<Row> findCases(@Param("postingId") long postingId, @Param("employerId") long employerId,
        @Param("workerId") long workerId, @Param("marker") String marker);

    interface Row {
        long getAssignmentId(); long getWorkScheduleId(); LocalDate getWorkDate();
        String getTimeZone(); LocalDateTime getStartAt(); LocalDateTime getEndAt();
        LocalDateTime getCompletedAt(); String getChangeType(); LocalDateTime getEffectiveAt();
        String getReasonCode(); String getWeatherReasonCode(); String getReasonDetail();
    }
}

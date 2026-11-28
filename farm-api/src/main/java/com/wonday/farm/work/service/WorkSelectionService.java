package com.wonday.farm.work.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import com.wonday.farm.application.repository.ApplicationConditionAcknowledgementRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.dto.WorkSelectionResponse;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.validation.SelectionInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkSelectionService {
    private final MemberRepository members;
    private final JobPostingRepository postings;
    private final WorkScheduleRepository schedules;
    private final JobApplicationRepository applications;
    private final ApplicationConditionAcknowledgementRepository acknowledgements;
    private final WorkSelectionRepository assignments;
    private final WorkChangeRepository changes;
    private final Clock clock;
    public WorkSelectionService(MemberRepository members, JobPostingRepository postings, WorkScheduleRepository schedules,
            JobApplicationRepository applications, ApplicationConditionAcknowledgementRepository acknowledgements,
            WorkSelectionRepository assignments, WorkChangeRepository changes, @Qualifier("jobClock") Clock clock) {
        this.members = members; this.postings = postings; this.schedules = schedules; this.applications = applications;
        this.acknowledgements = acknowledgements; this.assignments = assignments; this.changes = changes; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result select(long employerId, long postingId, long scheduleId, long applicationId, TermsVersionRequest request) {
        SelectionInput.require(employerId, postingId, scheduleId, applicationId);
        int expected = SelectionInput.version(request);
        members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
            .orElseThrow(() -> new AccessDeniedException("Employer role required"));

        // 지원・일정 수정과 같은 공고 → 일정 → 지원 순서다. 정원 조회와 INSERT까지 잠금을 유지한다.
        var posting = postings.findOwnedForUpdate(postingId, employerId).orElseThrow(() ->
            WorkSelectionException.missing("JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다."));
        var schedule = schedules.findByIdAndJobPosting_Id(scheduleId, postingId).orElseThrow(() ->
            WorkSelectionException.missing("WORK_SCHEDULE_NOT_FOUND", "해당 공고의 일정을 찾을 수 없습니다."));
        var application = applications.findScopedForUpdate(applicationId, scheduleId).orElseThrow(() ->
            WorkSelectionException.missing("APPLICATION_NOT_FOUND", "해당 날짜의 지원을 찾을 수 없습니다."));
        var previous = assignments.findByApplicationForUpdate(applicationId);
        if (previous.isPresent()) {
            // 기존 정상 결과 반환은 새 선정 검사를 적용하지 않는다. 원래 약정·시각을 그대로 읽는다.
            var assignment = previous.get();
            var change = changes.findForUpdate(assignment.getId());
            if (change.isPresent() && change.get().getChangeType() == WorkChange.Type.CANCELLED) {
                throw WorkSelectionException.conflict("WORK_ASSIGNMENT_CANCELLED", "취소된 확정 근무는 다시 선정할 수 없습니다.");
            }
            if (!"SELECTED".equals(application.getStatus())) {
                throw WorkSelectionException.conflict("APPLICATION_STATE_CONFLICT", "지원과 확정 근무 상태를 확인해 주세요.");
            }
            if ((assignment.getCompletedAtUtc() == null) != (assignment.getCompletedBy() == null)) {
                throw WorkSelectionException.unavailable();
            }
            String workStatus = change.isPresent() ? "EARLY_ENDED"
                : assignment.getCompletedAtUtc() != null ? "COMPLETED" : "CONFIRMED";
            return new Result(true, response(assignment, scheduleId, application.getWorkerId(), workStatus));
        }

        if (!application.isCurrent() || !"PENDING".equals(application.getStatus())) {
            throw WorkSelectionException.conflict("APPLICATION_NOT_PENDING", "현재 회차의 지원 대기자만 새로 선정할 수 있습니다.");
        }
        SelectionTerms.requireState(schedule);
        SelectionTerms.requireVersion(schedule, expected);
        members.findById(application.getWorkerId()).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
            .orElseThrow(() -> WorkSelectionException.conflict("WORKER_UNAVAILABLE", "선정할 근로자 계정을 확인할 수 없습니다."));
        boolean initialMatches = application.getAppliedTermsVersion() != null
            && application.getAppliedTermsVersion() == schedule.getTermsVersion();
        if (!initialMatches && acknowledgements.findByApplicationIdAndTermsVersion(applicationId, schedule.getTermsVersion()).isEmpty()) {
            throw WorkSelectionException.conflict("CONDITION_RECONFIRMATION_REQUIRED", "근로자가 최신 근무 조건을 재확인한 뒤 선정할 수 있습니다.");
        }
        SelectionTerms.requireConditions(schedule);
        SelectionTerms.requireCoordinates(schedule);
        if (schedules.countOccupyingAssignments(scheduleId) >= posting.getHeadcount()) {
            throw WorkSelectionException.conflict("CAPACITY_FULL", "확정 인원이 정원을 채웠습니다.");
        }
        if (applications.findBlockingAssignment(application.getWorkerId(), scheduleId).isPresent()) {
            throw WorkSelectionException.conflict("ALREADY_CONFIRMED", "해당 근로자에게 이미 유효한 확정 근무가 있습니다.");
        }
        // 잠금 대기·검사가 끝난 서버 시각으로 시작 경계를 판정한다.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SelectionTerms.requireBeforeStart(schedule, now);
        application.select();
        // 지원 변경과 약정 INSERT는 같은 트랜잭션이다. 실패 시 둘 다 롤백한다.
        var assignment = assignments.saveAndFlush(new WorkAssignment(applicationId, schedule, LocalDateTime.ofInstant(now, ZoneOffset.UTC)));
        return new Result(false, response(assignment, scheduleId, application.getWorkerId(), "CONFIRMED"));
    }
    private static WorkSelectionResponse response(WorkAssignment assignment, long scheduleId, long workerId, String state) {
        return new WorkSelectionResponse(assignment.getApplicationId(), scheduleId, workerId, "SELECTED", assignment.getId(),
            assignment.getAgreedTermsVersion(), display(assignment.getConfirmedAtUtc()), state, display(assignment.getCompletedAtUtc()));
    }
    private static java.time.OffsetDateTime display(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(ZoneOffset.ofHours(9));
    }
    public record Result(boolean replayed, WorkSelectionResponse body) {}
}

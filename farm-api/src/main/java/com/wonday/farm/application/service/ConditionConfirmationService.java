package com.wonday.farm.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import com.wonday.farm.application.dto.ConditionConfirmationResponse;
import com.wonday.farm.application.entity.ApplicationConditionAcknowledgement;
import com.wonday.farm.application.repository.ApplicationConditionAcknowledgementRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.SelectionTerms;
import com.wonday.farm.work.validation.SelectionInput;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConditionConfirmationService {
    private final MemberRepository members;
    private final JobPostingRepository postings;
    private final WorkScheduleRepository schedules;
    private final JobApplicationRepository applications;
    private final ApplicationConditionAcknowledgementRepository acknowledgements;
    private final WorkSelectionRepository assignments;
    private final JsonMapper mapper;
    private final Clock clock;
    public ConditionConfirmationService(MemberRepository members, JobPostingRepository postings, WorkScheduleRepository schedules,
            JobApplicationRepository applications, ApplicationConditionAcknowledgementRepository acknowledgements,
            WorkSelectionRepository assignments, JsonMapper mapper, @Qualifier("jobClock") Clock clock) {
        this.members = members; this.postings = postings; this.schedules = schedules; this.applications = applications;
        this.acknowledgements = acknowledgements; this.assignments = assignments; this.mapper = mapper; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConditionConfirmationResponse confirm(long workerId, long applicationId, TermsVersionRequest request) {
        SelectionInput.require(workerId, applicationId);
        int expected = SelectionInput.version(request);
        members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
            .orElseThrow(() -> new AccessDeniedException("Worker role required"));
        var location = applications.findOwnedLocation(workerId, applicationId).orElseThrow(ConditionConfirmationService::missing);
        // 소속을 먼저 확인한 뒤, 선정·일정 변경과 동일한 공고 → 일정 → 지원 잠금 순서를 사용한다.
        postings.findByIdForUpdate(location.getPostingId()).orElseThrow(ConditionConfirmationService::missing);
        var schedule = schedules.findByIdAndJobPosting_Id(location.getScheduleId(), location.getPostingId())
            .orElseThrow(ConditionConfirmationService::missing);
        var application = applications.findScopedForUpdate(applicationId, schedule.getId()).orElseThrow(ConditionConfirmationService::missing);
        if (application.getWorkerId() != workerId) throw missing();
        if (!application.isCurrent() || !"PENDING".equals(application.getStatus())
                || assignments.findByApplicationForUpdate(applicationId).isPresent()) {
            throw WorkSelectionException.conflict("APPLICATION_NOT_PENDING", "본인의 현재 회차 지원 대기 건만 재확인할 수 있습니다.");
        }
        SelectionTerms.requireState(schedule);
        SelectionTerms.requireVersion(schedule, expected);
        var previous = acknowledgements.findByApplicationIdAndTermsVersion(applicationId, expected).orElse(null);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        SelectionTerms.requireBeforeStart(schedule, now);
        if (application.getAppliedTermsVersion() != null && application.getAppliedTermsVersion() == expected) {
            // 최초 지원이 최신이면 재확인 기록·시각을 새로 만들지 않는다.
            return new ConditionConfirmationResponse(applicationId, application.getApplicationRound(), application.getStatus(),
                ConditionConfirmationMapper.of(application.getAppliedTermsVersion(), expected, null, null, null));
        }
        if (previous == null) {
            SelectionTerms.requireConditions(schedule);
            previous = acknowledgements.saveAndFlush(new ApplicationConditionAcknowledgement(applicationId, expected,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC), SelectionTerms.snapshot(schedule, mapper)));
        }
        // 같은 최신 버전의 반복 요청은 최초 기록 ID·시각·조건 원문을 그대로 유지한다.
        return new ConditionConfirmationResponse(applicationId, application.getApplicationRound(), application.getStatus(),
            ConditionConfirmationMapper.of(application.getAppliedTermsVersion(), expected, previous.getId(),
                previous.getTermsVersion(), previous.getAcknowledgedAtUtc()));
    }
    private static WorkSelectionException missing() {
        return WorkSelectionException.missing("APPLICATION_NOT_FOUND", "본인의 지원을 찾을 수 없습니다.");
    }
}

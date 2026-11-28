package com.wonday.farm.demo.cancellation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.wonday.farm.change.dto.WorkCancellationRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkCancellationException;
import com.wonday.farm.change.repository.WorkChangeRepository;
import com.wonday.farm.demo.selection.DemoWorkSelectionFixtureService;
import com.wonday.farm.insurance.service.InsuranceTime;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkSelectionService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 시연 전용 표식의 공고만 사용한다. 이미 취소·종료된 사례나 실제 업무 데이터를 초기화하지 않는다.
@Profile("farm-demo")
@Service
public class DemoWorkCancellationFixtureService {
    private static final List<String> SCENARIOS = List.of("NORMAL", "STALE_VERSION", "PAUSED",
        "STARTED", "CANCELLED", "EARLY_ENDED", "COMPLETED");
    private final MemberRepository members;
    private final DemoWorkSelectionFixtureService fixtures;
    private final WorkSelectionService selection;
    private final JobApplicationRepository applications;
    private final WorkScheduleRepository schedules;
    private final WorkSelectionRepository assignments;
    private final WorkChangeRepository changes;

    public DemoWorkCancellationFixtureService(MemberRepository members, DemoWorkSelectionFixtureService fixtures,
            WorkSelectionService selection, JobApplicationRepository applications, WorkScheduleRepository schedules,
            WorkSelectionRepository assignments, WorkChangeRepository changes) {
        this.members = members;
        this.fixtures = fixtures;
        this.selection = selection;
        this.applications = applications;
        this.schedules = schedules;
        this.assignments = assignments;
        this.changes = changes;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FixtureResponse prepare(long employerId) {
        members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
            .orElseThrow(() -> new AccessDeniedException("Employer role required"));
        var prepared = fixtures.prepare(employerId);
        if (prepared.preparedJobPostingId() == null) throw WorkCancellationException.unavailable();
        var result = new ArrayList<FixtureCase>();
        // 준비 응답의 공고 ID와 사례를 그대로 쓴다. 자정 이후 날짜를 다시 읽어 사례를 누락시키지 않는다.
        for (var item : prepared.cases()) {
            if (item.jobPostingId() != prepared.preparedJobPostingId() || !SCENARIOS.contains(item.scenario())) continue;
            var previous = assignments.findByApplicationForUpdate(item.applicationId());
            WorkAssignment assignment;
            if (previous.isPresent()) {
                assignment = previous.get();
            } else if ("STARTED".equals(item.scenario())) {
                // 이미 시작한 근무의 취소 거절을 확인하기 위한 전용 가상 약정이다.
                // 실제 선정 API의 시작 전 제한을 바꾸거나 우회 가능한 사용자 경로를 추가하지 않는다.
                var app = applications.findScopedForUpdate(item.applicationId(), item.workScheduleId())
                    .orElseThrow(WorkCancellationException::unavailable);
                var schedule = schedules.findByIdAndJobPosting_Id(item.workScheduleId(), item.jobPostingId())
                    .orElseThrow(WorkCancellationException::unavailable);
                if (!app.isCurrent() || !"PENDING".equals(app.getStatus())) throw WorkCancellationException.unavailable();
                app.select();
                var start = InsuranceTime.local(schedule.getPlannedStartAt(),
                    InsuranceTime.zone(schedule.getJobPosting().getTimeZone())).toInstant();
                assignment = assignments.saveAndFlush(new WorkAssignment(app.getId(), schedule,
                    java.time.LocalDateTime.ofInstant(start.minusSeconds(86400), ZoneOffset.UTC)));
            } else {
                var selected = selection.select(employerId, item.jobPostingId(), item.workScheduleId(),
                    item.applicationId(), new TermsVersionRequest(item.termsVersion()));
                assignment = assignments.findById(selected.body().assignmentId()).orElseThrow(WorkCancellationException::unavailable);
            }
            var change = changes.findForUpdate(assignment.getId());
            String state = change.isPresent() ? change.get().getChangeType().name()
                : assignment.getCompletedAtUtc() != null ? "COMPLETED" : "CONFIRMED";
            // 기존 취소 재요청은 실제 저장된 세 사유 필드를 그대로 사용하도록 안내한다.
            var request = change.filter(value -> value.getChangeType() == WorkChange.Type.CANCELLED)
                .map(value -> new WorkCancellationRequest(value.getReasonCode().name(),
                    value.getWeatherReasonCode() == null ? null : value.getWeatherReasonCode().name(), value.getReasonDetail()))
                .orElse(new WorkCancellationRequest("WEATHER", "RAIN", "강수로 작업을 취소합니다."));
            var zone = InsuranceTime.zone(assignment.getAgreedTimeZone());
            result.add(new FixtureCase(item.scenario(), label(item.scenario()), item.jobPostingId(),
                item.workScheduleId(), assignment.getId(), state,
                InsuranceTime.local(assignment.getAgreedStartAt(), zone), request));
        }
        return new FixtureResponse(prepared.created(), prepared.preparedJobPostingId(), List.copyOf(result));
    }

    private static String label(String scenario) {
        return switch (scenario) {
            case "NORMAL" -> "최초 취소·동일 재요청·다른 사유 409";
            case "STALE_VERSION" -> "다른 정상 확정 근무의 상태 유지 확인";
            case "PAUSED" -> "일시중단 날짜의 개인별 취소";
            case "STARTED" -> "시작 후 최초 취소 거절 409";
            case "CANCELLED" -> "기존 취소의 동일 내용 재요청";
            case "EARLY_ENDED" -> "조기 종료 근무 취소 거절 409";
            case "COMPLETED" -> "완료 근무 취소 거절 409";
            default -> throw WorkCancellationException.unavailable();
        };
    }

    public record FixtureResponse(boolean created, long jobPostingId, List<FixtureCase> cases) {}
    public record FixtureCase(String scenario, String label, long jobPostingId, long workScheduleId,
        long assignmentId, String workStatus, OffsetDateTime agreedStartAt, WorkCancellationRequest suggestedRequest) {}
}

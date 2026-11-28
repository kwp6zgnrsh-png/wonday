package com.wonday.farm.demo.selection;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.demo.applicant.DemoWorkApplicantFixtureRepository;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.entity.WorkScheduleRevision;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.repository.WorkScheduleRevisionRepository;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.exception.WorkSelectionException;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkCompletionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
public class DemoWorkSelectionFixtureService {
    private static final List<String> TYPES = List.of("NORMAL", "CHANGED", "STALE_VERSION", "PAUSED", "CLOSED",
        "REMOVED", "STARTED", "CANCELLED", "EARLY_ENDED", "COMPLETED", "CAPACITY_FULL");
    private static final List<String> LABELS = List.of("정상 선정·같은 선정 재요청", "변경 조건 재확인 후 선정",
        "이전 버전 요청 409", "일시중단 상태 선정", "마감 상태 409", "모집 취소 상태 409",
        "시작 시각 경과 409", "취소된 확정 근무 409", "조기 종료 근무 재요청 200",
        "완료 근무 재요청 200", "정원 충족 409");
    private final MemberRepository members;
    private final JobPostingRepository postings;
    private final WorkScheduleRepository schedules;
    private final WorkScheduleRevisionRepository revisions;
    private final JobApplicationRepository applications;
    private final WorkSelectionRepository assignments;
    private final ApplicationRequestLockRepository locks;
    private final DemoJobApplicationRepository lookup;
    private final DemoWorkApplicantFixtureRepository statuses;
    private final DemoWorkSelectionRepository fixtures;
    private final WorkChangeRecorder changes;
    private final WorkCompletionService completion;
    private final PasswordEncoder passwords;
    private final Clock clock;
    public DemoWorkSelectionFixtureService(MemberRepository members, JobPostingRepository postings,
            WorkScheduleRepository schedules, WorkScheduleRevisionRepository revisions, JobApplicationRepository applications,
            WorkSelectionRepository assignments, ApplicationRequestLockRepository locks, DemoJobApplicationRepository lookup,
            DemoWorkApplicantFixtureRepository statuses, DemoWorkSelectionRepository fixtures, WorkChangeRecorder changes,
            WorkCompletionService completion, PasswordEncoder passwords, @Qualifier("jobClock") Clock clock) {
        this.members=members; this.postings=postings; this.schedules=schedules; this.revisions=revisions;
        this.applications=applications; this.assignments=assignments; this.locks=locks; this.lookup=lookup;
        this.statuses=statuses; this.fixtures=fixtures; this.changes=changes; this.completion=completion;
        this.passwords=passwords; this.clock=clock;
    }

    @Transactional(readOnly=true, isolation=Isolation.READ_COMMITTED)
    public FixtureResponse list(long actorId) {
        requireActor(actorId);
        return response(actorId, false, null);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public FixtureResponse prepare(long actorId) {
        var actor=requireActor(actorId);
        boolean employerActor=actor.memberRole().orElseThrow()==MemberRole.EMPLOYER;
        var employer=employerActor ? actor : counterpart("demo-employer",MemberRole.EMPLOYER);
        var worker=employerActor ? counterpart("demo-worker",MemberRole.WORKER) : actor;
        // APP-02 준비와 같은 근로자별 잠금부터 잡는다. 기존 회원 정보나 업무 이력은 바꾸지 않는다.
        locks.ensureExists(worker.getId());
        locks.lockWorker(worker.getId()).orElseThrow(WorkSelectionException::unavailable);
        var instant=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var now=LocalDateTime.ofInstant(instant,ZoneOffset.UTC);
        var today=LocalDate.ofInstant(instant,ZoneOffset.ofHours(9));
        String marker="[WORK-02 시연·고용주 "+employer.getId()+"·근로자 "+worker.getId()+"·"+today+"]";
        var existing=lookup.findFixturePosting(employer.getId(),marker);
        if(existing.isPresent())return response(actorId,false,existing.get());

        var dates=new ArrayList<CreateJobPostingRequest.Schedule>();
        for(int i=0;i<TYPES.size();i++){
            var day=switch(TYPES.get(i)){case "STARTED"->today.minusDays(1);case "EARLY_ENDED"->today.minusDays(2);
                case "COMPLETED"->today.minusDays(3);default->today.plusDays(7+i);};
            dates.add(day(day,9,17));
        }
        var request=new CreateJobPostingRequest(marker,"실제 모집이 아닌 선정·재확인 기능 시연용 가상 공고입니다.",
            "FC03030100030103","0003","WORK-02 가상 작업지",new BigDecimal("37.000000"),
            new BigDecimal("127.000000"),"GMT+9",1,120000L,dates);
        var posting=postings.saveAndFlush(new JobPosting(employer,request));
        var saved=schedules.saveAllAndFlush(dates.stream().map(date->new WorkSchedule(posting,date)).toList());
        for(int i=0;i<TYPES.size();i++){
            var type=TYPES.get(i);var schedule=saved.get(i);
            if(type.equals("STALE_VERSION"))changeTime(schedule,employer.getId(),now);
            var app=applications.saveAndFlush(new JobApplication(worker.getId(),schedule.getId(),1,schedule.getTermsVersion(),now.minusDays(5)));
            if(type.equals("CHANGED"))changeTime(schedule,employer.getId(),now);
            if(List.of("CANCELLED","EARLY_ENDED","COMPLETED").contains(type)){
                app.select();
                var assignment=assignments.saveAndFlush(new WorkAssignment(app.getId(),schedule,now.minusDays(4)));
                if(type.equals("COMPLETED"))completion.complete(employer.getId(),assignment.getId());
                else changes.recordValidatedChange(employer.getId(),assignment.getId(),
                    type.equals("CANCELLED")?WorkChange.Type.CANCELLED:WorkChange.Type.EARLY_ENDED,
                    type.equals("EARLY_ENDED")?schedule.getWorkDate().atTime(14,0).atOffset(ZoneOffset.ofHours(9)):null,
                    WorkChange.Reason.WEATHER,WorkChange.WeatherReason.RAIN,"[WORK-02 시연] 가상 기상 변경");
            }
            if(List.of("PAUSED","CLOSED","REMOVED").contains(type)){
                if(statuses.setNewScheduleStatus(employer.getId(),posting.getId(),schedule.getId(),marker,type,now)!=1)
                    throw WorkSelectionException.unavailable();
            }
            if(type.equals("CAPACITY_FULL")){
                // 이 준비 작업에서만 쓰는 새 가상 근로자다. 기존 계정의 비밀번호를 수정하지 않는다.
                var other=members.saveAndFlush(new Member("demo-selection-"+UUID.randomUUID(),
                    passwords.encode(UUID.randomUUID().toString()),"정원 확인용 가상 근로자",MemberRole.WORKER));
                var occupied=applications.saveAndFlush(new JobApplication(other.getId(),schedule.getId(),1,schedule.getTermsVersion(),now.minusDays(5)));
                occupied.select();
                assignments.saveAndFlush(new WorkAssignment(occupied.getId(),schedule,now.minusDays(4)));
            }
        }
        return response(actorId,true,posting.getId());
    }
    private void changeTime(WorkSchedule schedule,long actorId,LocalDateTime now) {
        revisions.save(WorkScheduleRevision.baseline(schedule,now));
        schedule.updateConditions(day(schedule.getWorkDate(),10,18));
        revisions.save(WorkScheduleRevision.updated(schedule,actorId,now));
        schedules.saveAndFlush(schedule);
    }
    private Member requireActor(long id) {
        return members.findById(id).filter(m->m.memberRole().orElse(null)==MemberRole.EMPLOYER
            ||m.memberRole().orElse(null)==MemberRole.WORKER).orElseThrow(()->new AccessDeniedException("Farm role required"));
    }
    private Member counterpart(String login,MemberRole role) {
        return members.findByLoginId(login).filter(m->m.memberRole().orElse(null)==role).orElseThrow(()->
            WorkSelectionException.conflict("DEMO_ACCOUNT_UNAVAILABLE","farm-demo의 demo-employer·demo-worker 계정을 확인해 주세요."));
    }
    private FixtureResponse response(long actorId,boolean created,Long postingId) {
        var today=LocalDate.ofInstant(clock.instant(),ZoneOffset.ofHours(9));
        Map<Long,Integer> indexes=new HashMap<>();
        var cases=fixtures.findCases(actorId,today).stream().map(row->{
            int index=indexes.merge(row.getJobPostingId(),1,Integer::sum)-1;
            if(index>=TYPES.size())throw WorkSelectionException.unavailable();
            return new Case(TYPES.get(index),LABELS.get(index),row.getJobPostingId(),row.getWorkScheduleId(),row.getApplicationId(),
                row.getWorkerId(),row.getWorkerName(),row.getEmployerId(),row.getEmployerName(),row.getWorkDate(),
                row.getRecruitmentStatus(),row.getTermsVersion(),row.getAppliedTermsVersion(),row.getApplicationStatus(),
                row.getAssignmentId(),row.getWorkStatus());
        }).toList();
        return new FixtureResponse(created,postingId,cases);
    }
    private static CreateJobPostingRequest.Schedule day(LocalDate date,int start,int end) {
        return new CreateJobPostingRequest.Schedule(date,date.atTime(start,0),date.atTime(end,0),date.atTime(12,0),date.atTime(13,0));
    }
    public record FixtureResponse(boolean created,Long preparedJobPostingId,List<Case> cases) {}
    public record Case(String scenario,String label,long jobPostingId,long workScheduleId,long applicationId,long workerId,
        String workerName,long employerId,String employerName,LocalDate workDate,String recruitmentStatus,int termsVersion,
        Integer appliedTermsVersion,String applicationStatus,Long assignmentId,String workStatus) {}
}

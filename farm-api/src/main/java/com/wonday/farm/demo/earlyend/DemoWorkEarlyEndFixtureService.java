package com.wonday.farm.demo.earlyend;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.wonday.farm.application.entity.JobApplication;
import com.wonday.farm.application.repository.ApplicationRequestLockRepository;
import com.wonday.farm.application.repository.JobApplicationRepository;
import com.wonday.farm.change.dto.WorkEarlyEndRequest;
import com.wonday.farm.change.entity.WorkChange;
import com.wonday.farm.change.exception.WorkEarlyEndException;
import com.wonday.farm.change.service.WorkChangeRecorder;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.insurance.service.InsuranceTime;
import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.work.entity.WorkAssignment;
import com.wonday.farm.work.repository.WorkSelectionRepository;
import com.wonday.farm.work.service.WorkCompletionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
public class DemoWorkEarlyEndFixtureService {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);
    private static final List<String> TYPES = List.of("NORMAL", "LATE_ALLOWED", "NOT_STARTED",
        "DEADLINE_PASSED", "CANCELLED", "EARLY_ENDED", "COMPLETED");
    private final MemberRepository members;
    private final ApplicationRequestLockRepository locks;
    private final DemoJobApplicationRepository lookup;
    private final JobPostingRepository postings;
    private final WorkScheduleRepository schedules;
    private final JobApplicationRepository applications;
    private final WorkSelectionRepository assignments;
    private final WorkChangeRecorder recorder;
    private final WorkCompletionService completion;
    private final DemoWorkEarlyEndRepository fixtures;
    private final Clock clock;

    public DemoWorkEarlyEndFixtureService(MemberRepository members, ApplicationRequestLockRepository locks,
            DemoJobApplicationRepository lookup, JobPostingRepository postings, WorkScheduleRepository schedules,
            JobApplicationRepository applications, WorkSelectionRepository assignments, WorkChangeRecorder recorder,
            WorkCompletionService completion, DemoWorkEarlyEndRepository fixtures,
            @Qualifier("insuranceChangeClock") Clock clock) {
        this.members=members;this.locks=locks;this.lookup=lookup;this.postings=postings;this.schedules=schedules;
        this.applications=applications;this.assignments=assignments;this.recorder=recorder;
        this.completion=completion;this.fixtures=fixtures;this.clock=clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FixtureResponse prepare(long employerId) {
        var employer=members.findById(employerId).filter(member->member.memberRole().orElse(null)==MemberRole.EMPLOYER)
            .orElseThrow(()->new AccessDeniedException("Employer role required"));
        var worker=members.findByLoginId("demo-worker").filter(member->member.memberRole().orElse(null)==MemberRole.WORKER)
            .orElseThrow(()->WorkEarlyEndException.conflict("DEMO_ACCOUNT_UNAVAILABLE","farm-demo의 demo-worker 계정을 확인해 주세요."));
        locks.ensureExists(worker.getId());
        locks.lockWorker(worker.getId()).orElseThrow(WorkEarlyEndException::unavailable);
        var instant=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var today=LocalDate.ofInstant(instant,OFFSET);
        String marker="[CHANGE-03 시연·고용주 "+employerId+"·근로자 "+worker.getId()+"·"+today+"]";
        var existing=lookup.findFixturePosting(employerId,marker);
        if(existing.isPresent())return response(false,existing.get(),employerId,worker.getId(),marker,today,instant);

        var dates=dates(today);
        var conditions=TYPES.stream().map(type->day(dates.get(type),List.of("NORMAL","LATE_ALLOWED").contains(type))).toList();
        var request=new CreateJobPostingRequest(marker,"실제 모집이 아닌 조기 종료 시연용 가상 공고입니다.",
            "FC03030100030103","0003","CHANGE-03 가상 작업지",new BigDecimal("37.000000"),
            new BigDecimal("127.000000"),"GMT+9",1,120000L,conditions);
        var posting=postings.saveAndFlush(new JobPosting(employer,request));
        var saved=schedules.saveAllAndFlush(conditions.stream().map(value->new WorkSchedule(posting,value)).toList());
        for(int index=0;index<TYPES.size();index++){
            var type=TYPES.get(index);
            var schedule=saved.get(index);
            var confirmedAt=schedule.getPlannedStartAt().toInstant(OFFSET).minusSeconds(86400);
            var app=applications.saveAndFlush(new JobApplication(worker.getId(),schedule.getId(),1,schedule.getTermsVersion(),
                LocalDateTime.ofInstant(confirmedAt.minusSeconds(3600),ZoneOffset.UTC)));
            app.select();
            // 시작 경과·과거 기록을 즉시 확인하기 위한 가상 약정이다. 운영 선정 규칙은 변경하지 않는다.
            var assignment=assignments.saveAndFlush(new WorkAssignment(app.getId(),schedule,
                LocalDateTime.ofInstant(confirmedAt,ZoneOffset.UTC)));
            if(type.equals("CANCELLED")||type.equals("EARLY_ENDED")){
                // 기존 기록 재요청을 확인하는 시연 전용 입력이다. 신규 사용자 요청은 endWork의 기한 검사를 거친다.
                recorder.recordValidatedChange(employerId,assignment.getId(),
                    type.equals("CANCELLED")?WorkChange.Type.CANCELLED:WorkChange.Type.EARLY_ENDED,
                    type.equals("EARLY_ENDED")?schedule.getWorkDate().atTime(14,0).atOffset(OFFSET):null,
                    WorkChange.Reason.WEATHER,WorkChange.WeatherReason.RAIN,"[CHANGE-03 시연] 기존 가상 기상 변경");
            }else if(type.equals("COMPLETED"))completion.complete(employerId,assignment.getId());
        }
        return response(true,posting.getId(),employerId,worker.getId(),marker,today,instant);
    }

    private FixtureResponse response(boolean created,long postingId,long employerId,long workerId,String marker,
            LocalDate basisDay,java.time.Instant basisInstant) {
        var dates=dates(basisDay);
        var rows=fixtures.findCases(postingId,employerId,workerId,marker).stream().map(row->{
            String type=TYPES.stream().filter(value->dates.get(value).equals(row.getWorkDate())).findFirst()
                .orElseThrow(WorkEarlyEndException::unavailable);
            var zone=InsuranceTime.zone(row.getTimeZone());
            var start=InsuranceTime.local(row.getStartAt(),zone);
            var end=InsuranceTime.local(row.getEndAt(),zone);
            OffsetDateTime suggested;
            if("EARLY_ENDED".equals(row.getChangeType())){
                suggested=InsuranceTime.utc(row.getEffectiveAt(),zone);
            }else if(type.equals("NORMAL")){
                var candidate=basisInstant.minusSeconds(60);
                if(candidate.isBefore(start.toInstant()))candidate=start.toInstant();
                if(!candidate.isBefore(end.toInstant()))candidate=end.toInstant().minusNanos(1000);
                suggested=candidate.atZone(zone).toOffsetDateTime();
            }else if(type.equals("NOT_STARTED")||type.equals("CANCELLED")){
                suggested=start;
            }else{
                suggested=end.minusHours(1);
            }
            boolean previous="EARLY_ENDED".equals(row.getChangeType());
            var body=new WorkEarlyEndRequest(suggested,previous?row.getReasonCode():"WEATHER",
                previous?row.getWeatherReasonCode():"RAIN",previous?row.getReasonDetail():"기상으로 작업을 조기 종료합니다.");
            String state=row.getChangeType()!=null?row.getChangeType():row.getCompletedAt()!=null?"COMPLETED":"CONFIRMED";
            return new FixtureCase(type,label(type),row.getAssignmentId(),row.getWorkScheduleId(),state,start,end,
                end.toInstant().plusSeconds(86400).atZone(zone).toOffsetDateTime(),body);
        }).sorted(Comparator.comparingInt(value->TYPES.indexOf(value.scenario()))).toList();
        return new FixtureResponse(created,postingId,rows);
    }

    private static Map<String,LocalDate> dates(LocalDate today) {
        return Map.of("NORMAL",today,"LATE_ALLOWED",today.minusDays(1),"NOT_STARTED",today.plusDays(1),
            "DEADLINE_PASSED",today.minusDays(2),"CANCELLED",today.plusDays(2),
            "EARLY_ENDED",today.minusDays(3),"COMPLETED",today.minusDays(4));
    }
    private static CreateJobPostingRequest.Schedule day(LocalDate date,boolean wholeDay) {
        return new CreateJobPostingRequest.Schedule(date,wholeDay?date.atStartOfDay():date.atTime(9,0),
            wholeDay?date.atTime(23,59,59,999999000):date.atTime(17,0),null,null);
    }
    private static String label(String type) {
        return switch(type) {
            case "NORMAL"->"최초 조기 종료 200·동일 재요청";
            case "LATE_ALLOWED"->"약정 종료 후 24시간 이내 늦은 입력";
            case "NOT_STARTED"->"시작 전 409";
            case "DEADLINE_PASSED"->"입력 기한 초과 409·문의 안내";
            case "CANCELLED"->"취소 근무 거절 409";
            case "EARLY_ENDED"->"기존 조기 종료·기한 이후 동일 재요청";
            case "COMPLETED"->"완료 근무 거절 409";
            default->throw WorkEarlyEndException.unavailable();
        };
    }
    public record FixtureResponse(boolean created,long jobPostingId,List<FixtureCase> cases) {}
    public record FixtureCase(String scenario,String label,long assignmentId,long workScheduleId,String workStatus,
        OffsetDateTime agreedStartAt,OffsetDateTime agreedEndAt,OffsetDateTime recordingDeadline,WorkEarlyEndRequest suggestedRequest) {}
}

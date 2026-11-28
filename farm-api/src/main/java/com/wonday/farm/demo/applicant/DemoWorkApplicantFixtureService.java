package com.wonday.farm.demo.applicant;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.wonday.farm.application.exception.ApplicationException;
import com.wonday.farm.demo.application.DemoJobApplicationRepository;
import com.wonday.farm.demo.application.DemoMyApplicationFixtureRepository;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("farm-demo")
@Service
public class DemoWorkApplicantFixtureService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final WorkScheduleRevisionRepository revisions;
	private final DemoWorkApplicantFixtureRepository fixtures;
	private final DemoJobApplicationRepository lookup;
	private final DemoMyApplicationFixtureRepository assignmentFixtures;
	private final PasswordEncoder passwords;
	private final Clock clock;
	public DemoWorkApplicantFixtureService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, WorkScheduleRevisionRepository revisions,
			DemoWorkApplicantFixtureRepository fixtures, DemoJobApplicationRepository lookup,
			DemoMyApplicationFixtureRepository assignmentFixtures, PasswordEncoder passwords,
			@Qualifier("jobClock") Clock clock) {
		this.members=members;this.postings=postings;this.schedules=schedules;this.revisions=revisions;this.fixtures=fixtures;
		this.lookup=lookup;this.assignmentFixtures=assignmentFixtures;this.passwords=passwords;this.clock=clock;
	}
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public FixtureResponse prepare(long employerId) {
		members.findById(employerId).filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var employer=fixtures.lockEmployer(employerId).orElseThrow(() -> new AccessDeniedException("Employer role required"));
		var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
		var nowUtc=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
		var today=LocalDate.ofInstant(now,ZoneOffset.ofHours(9));
		String marker="[WORK-01 시연·고용주 "+employerId+"·"+today+"]";
		var existing=lookup.findFixturePosting(employerId,marker);
		if(existing.isPresent()) return response(employerId,existing.get(),false);

		// 새 가상 회원만 만든다. 기존 근로자 이름·연락처·비밀번호는 변경하지 않는다.
		String hash=passwords.encode(UUID.randomUUID().toString());
		var workers=new ArrayList<Member>();
		for(int i=1;i<=5;i++) workers.add(new Member("demo-applicant-"+UUID.randomUUID(),hash,"시연 근로자 "+i,
			i==5 ? null : "0100000100"+i,MemberRole.WORKER));
		members.saveAllAndFlush(workers);
		var dates=List.of(day(today.plusDays(7)),day(today.plusDays(8)),day(today.minusDays(1)),day(today.plusDays(9)));
		var request=new CreateJobPostingRequest(marker,"실제 모집이 아닌 고용주 지원자 조회용 가상 공고입니다.",
			"FC03030100030103","0003","WORK-01 가상 작업지",new BigDecimal("37.000000"),new BigDecimal("127.000000"),
			"GMT+9",3,120000L,dates);
		var posting=postings.saveAndFlush(new JobPosting(employer,request));
		var created=schedules.saveAllAndFlush(dates.stream().map(date -> new WorkSchedule(posting,date)).toList());
		long postingId=posting.getId();
		var first=created.getFirst();
		// 첫 근로자는 v1에 지원했고 이후 v2로 변경된 사례다.
		add(employerId,postingId,marker,first,workers.get(0),"PENDING",1,1,true,nowUtc.minusMinutes(30));
		revisions.save(WorkScheduleRevision.baseline(first,nowUtc.minusMinutes(25)));
		var changedDay=first.getWorkDate();
		first.updateConditions(new CreateJobPostingRequest.Schedule(changedDay,changedDay.atTime(10,0),changedDay.atTime(18,0),
			changedDay.atTime(12,0),changedDay.atTime(13,0)));
		revisions.save(WorkScheduleRevision.updated(first,employerId,nowUtc.minusMinutes(25)));
		schedules.saveAndFlush(first);
		add(employerId,postingId,marker,first,workers.get(1),"PENDING",1,2,true,nowUtc.minusMinutes(20));
		long selected=add(employerId,postingId,marker,first,workers.get(2),"SELECTED",1,2,true,nowUtc.minusMinutes(15));
		if(assignmentFixtures.insertAssignment(selected,workers.get(2).getId(),postingId,employerId,marker,nowUtc.minusMinutes(14))!=1) {
			throw ApplicationException.unavailable();
		}
		add(employerId,postingId,marker,first,workers.get(3),"NOT_SELECTED",1,2,true,nowUtc.minusMinutes(10));
		add(employerId,postingId,marker,first,workers.get(4),"NOT_SELECTED",1,1,false,nowUtc.minusMinutes(40));
		add(employerId,postingId,marker,first,workers.get(4),"PENDING",2,2,true,nowUtc.minusMinutes(10));
		add(employerId,postingId,marker,created.get(1),workers.get(0),"PENDING",1,1,true,nowUtc.minusMinutes(9));
		add(employerId,postingId,marker,created.get(2),workers.get(1),"NOT_SELECTED",1,1,true,nowUtc.minusDays(2));
		add(employerId,postingId,marker,created.get(3),workers.get(3),"PENDING",1,1,true,nowUtc.minusMinutes(8));
		setStatus(employerId,postingId,created.get(1).getId(),marker,"PAUSED",nowUtc);
		setStatus(employerId,postingId,created.get(2).getId(),marker,"CLOSED",nowUtc);
		setStatus(employerId,postingId,created.get(3).getId(),marker,"REMOVED",nowUtc);
		return response(employerId,postingId,true);
	}
	private long add(long employerId,long postingId,String marker,WorkSchedule schedule,Member worker,String status,
			int round,int version,boolean current,LocalDateTime appliedAt) {
		if(fixtures.insertApplication(worker.getId(),schedule.getId(),postingId,employerId,marker,status,round,version,current?1:0,appliedAt)!=1) {
			throw ApplicationException.unavailable();
		}
		return assignmentFixtures.findApplicationId(worker.getId(),schedule.getId(),round).orElseThrow(ApplicationException::unavailable);
	}
	private void setStatus(long employerId,long postingId,long scheduleId,String marker,String status,LocalDateTime now) {
		if(fixtures.setNewScheduleStatus(employerId,postingId,scheduleId,marker,status,now)!=1) throw ApplicationException.unavailable();
	}
	private FixtureResponse response(long employerId,long postingId,boolean created) {
		var dates=fixtures.findDates(employerId,postingId).stream()
			.map(row -> new Date(row.getWorkScheduleId(),row.getWorkDate(),row.getStatus(),row.getTermsVersion())).toList();
		var cases=fixtures.findCases(employerId,postingId).stream().map(row -> {
			String label=row.getCurrentFlag()!=1 ? "과거 회차: 목록 제외·상세 404·연락처 403"
				: row.getPhonePresent()==0 ? "재지원 현재 회차·연락처 미등록"
				: "SELECTED".equals(row.getStatus()) ? "선정·확정 약정"
				: "NOT_SELECTED".equals(row.getStatus()) ? "미선정·연락처 403"
				: row.getAppliedTermsVersion()!=null && row.getAppliedTermsVersion()!=row.getLatestTermsVersion()
					? "지원 후 조건 변경" : "지원 대기";
			return new Case(label,row.getApplicationId(),row.getWorkScheduleId(),row.getName(),row.getStatus(),
				row.getApplicationRound(),row.getCurrentFlag()==1);
		}).toList();
		return new FixtureResponse(postingId,created,dates,cases);
	}
	private static CreateJobPostingRequest.Schedule day(LocalDate day) {
		return new CreateJobPostingRequest.Schedule(day,day.atTime(9,0),day.atTime(17,0),day.atTime(12,0),day.atTime(13,0));
	}
	public record FixtureResponse(long jobPostingId,boolean created,List<Date> schedules,List<Case> scenarios) {}
	public record Date(long workScheduleId,LocalDate workDate,String status,int termsVersion) {}
	public record Case(String label,long applicationId,long workScheduleId,String name,String status,int applicationRound,boolean current) {}
}

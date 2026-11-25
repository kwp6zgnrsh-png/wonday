package com.wonday.farm.job.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import com.wonday.farm.job.dto.CreateJobPostingResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.JobPostingException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.job.validation.WorkScheduleValidator;
import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class JobPostingService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;

	public JobPostingService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
	}

	@Transactional
	public CreateJobPostingResponse create(long employerId, @Valid CreateJobPostingRequest request) {
		// 세션의 ID로 실제 회원을 확인한다. 계정 역할이 바뀐 경우에도 현재 DB 역할을 적용한다.
		Member employer = members.findById(employerId)
			.filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));
		validate(request);

		// 전체 조건을 검증한 다음 저장하며, 일정 저장 실패 시 공고 저장도 함께 롤백된다.
		JobPosting posting = postings.save(new JobPosting(employer, request));
		var savedSchedules = schedules.saveAll(request.schedules().stream()
			.map(schedule -> new WorkSchedule(posting, schedule)).toList());
		return new CreateJobPostingResponse(posting.getId(), employer.getId(), posting.getHeadcount(),
			savedSchedules.stream().map(schedule -> new CreateJobPostingResponse.Schedule(
				schedule.getId(), schedule.getWorkDate(), schedule.getStatus())).toList());
	}

	private static void validate(CreateJobPostingRequest request) {
		// 팀에서 합의한 고정 표기만 받고, 저장할 때도 입력한 GMT+9를 유지한다.
		if (!"GMT+9".equals(request.timeZone())) {
			throw new JobPostingException("INVALID_TIME_ZONE", "작업지 시간대는 GMT+9로 입력해 주세요.", "timeZone");
		}
		ZoneId zone = ZoneId.of(request.timeZone());
		if (request.description().getBytes(StandardCharsets.UTF_8).length > 65535) {
			throw new JobPostingException("INVALID_JOB_POSTING", "작업 내용이 저장 가능한 길이를 초과했습니다.", "description");
		}
		var dates = new HashSet<LocalDate>();
		for (int index = 0; index < request.schedules().size(); index++) {
			var schedule = request.schedules().get(index);
			String field = "schedules[" + index + "]";
			if (!dates.add(schedule.workDate())) {
				throw new JobPostingException("DUPLICATE_WORK_DATE", "같은 근무일을 두 번 등록할 수 없습니다.", field + ".workDate");
			}
			WorkScheduleValidator.validate(schedule, zone, field);
		}
	}
}

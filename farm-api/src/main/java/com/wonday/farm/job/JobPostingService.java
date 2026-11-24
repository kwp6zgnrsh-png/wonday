package com.wonday.farm.job;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;

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
			validateWorkTime(schedule, zone, field);
			validateBreakTime(schedule, zone, field);
		}
	}

	private static void validateWorkTime(CreateJobPostingRequest.Schedule schedule, ZoneId zone, String field) {
		validateLocalTime(schedule.plannedStartAt(), zone, field + ".plannedStartAt");
		validateLocalTime(schedule.plannedEndAt(), zone, field + ".plannedEndAt");
		if (!schedule.plannedStartAt().toLocalDate().equals(schedule.workDate())) {
			throw new JobPostingException("INVALID_WORK_TIME", "근무일은 예정 시작 일시의 날짜와 같아야 합니다.", field + ".workDate");
		}
		if (!schedule.plannedEndAt().isAfter(schedule.plannedStartAt())) {
			throw new JobPostingException("INVALID_WORK_TIME", "예정 종료 일시는 시작 일시 이후여야 합니다.", field + ".plannedEndAt");
		}
	}

	private static void validateBreakTime(CreateJobPostingRequest.Schedule schedule, ZoneId zone, String field) {
		var start = schedule.breakStartAt();
		var end = schedule.breakEndAt();
		if (start == null && end == null) {
			return;
		}
		if (start == null) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 시작을 입력해 주세요.", field + ".breakStartAt");
		}
		if (end == null) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료를 입력해 주세요.", field + ".breakEndAt");
		}
		validateLocalTime(start, zone, field + ".breakStartAt");
		validateLocalTime(end, zone, field + ".breakEndAt");
		if (!end.isAfter(start)) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료는 휴게 시작 이후여야 합니다.", field + ".breakEndAt");
		}
		if (start.isBefore(schedule.plannedStartAt())) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 시작은 근무 시작 이전일 수 없습니다.", field + ".breakStartAt");
		}
		if (end.isAfter(schedule.plannedEndAt())) {
			throw new JobPostingException("INVALID_BREAK_TIME", "휴게 종료는 근무 종료를 넘을 수 없습니다.", field + ".breakEndAt");
		}
	}

	private static void validateLocalTime(LocalDateTime time, ZoneId zone, String field) {
		// MySQL DATETIME의 범위·정밀도와 작업지 시간대에서 해석 가능한 일시인지 확인한다.
		if (time.getYear() < 1000 || time.getYear() > 9999 || time.getNano() % 1000 != 0
				|| zone.getRules().getValidOffsets(time).size() != 1) {
			throw new JobPostingException("INVALID_LOCAL_TIME", "작업지에서 해석 가능한 일시를 마이크로초 정밀도 이내로 입력해 주세요.", field);
		}
	}
}

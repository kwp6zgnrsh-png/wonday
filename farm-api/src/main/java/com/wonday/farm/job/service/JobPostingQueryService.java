package com.wonday.farm.job.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.wonday.farm.job.dto.MyJobPostingsResponse;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobPostingQueryService {
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;

	public JobPostingQueryService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public MyJobPostingsResponse findMine(long employerId) {
		// 세션 발급 후 역할이 변경된 계정도 현재 DB의 역할을 기준으로 확인한다.
		members.findById(employerId)
			.filter(member -> member.memberRole().orElse(null) == MemberRole.EMPLOYER)
			.orElseThrow(() -> new AccessDeniedException("Employer role required"));

		var ownedPostings = postings.findByEmployer_IdOrderByIdDesc(employerId);
		if (ownedPostings.isEmpty()) {
			return new MyJobPostingsResponse(List.of());
		}

		var countsBySchedule = schedules.countOwnedSchedules(employerId).stream()
			.collect(Collectors.toMap(WorkScheduleRepository.ScheduleCounts::getWorkScheduleId, Function.identity()));
		Map<Long, List<MyJobPostingsResponse.Schedule>> schedulesByPosting = new HashMap<>();
		for (var schedule : schedules.findOwnedSchedules(employerId)) {
			var posting = schedule.getJobPosting();
			var counts = countsBySchedule.get(schedule.getId());
			// 집계 결과가 누락된 일정도 조회할 수 있도록 지원자 수와 확정 인원을 0으로 처리한다.
			long applicants = counts == null ? 0 : counts.getApplicantCount();
			// 확정 인원은 확정 근무의 생성 건수다. 취소 후 재모집 여부는 별도 정책이 필요하다.
			long confirmed = counts == null ? 0 : counts.getConfirmedCount();
			var summary = new MyJobPostingsResponse.Schedule(schedule.getId(), schedule.getWorkDate(),
				schedule.getPlannedStartAt(), schedule.getPlannedEndAt(), schedule.getBreakStartAt(),
				schedule.getBreakEndAt(), schedule.getStatus(), schedule.getTermsVersion(),
				applicants, confirmed, Math.max((long) posting.getHeadcount() - confirmed, 0));
			schedulesByPosting.computeIfAbsent(posting.getId(), id -> new ArrayList<>()).add(summary);
		}

		return new MyJobPostingsResponse(ownedPostings.stream().map(posting ->
			new MyJobPostingsResponse.Posting(posting.getId(), posting.getTitle(), posting.getDescription(),
				posting.getCropCode(), posting.getWorkTypeCode(), posting.getWorkLocation(), posting.getTimeZone(),
				posting.getHeadcount(), posting.getDailyWage(),
				schedulesByPosting.getOrDefault(posting.getId(), List.of()))).toList());
	}
}

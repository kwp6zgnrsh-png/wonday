package com.wonday.farm.job.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.wonday.farm.job.dto.WorkerJobPostingResponse;
import com.wonday.farm.job.dto.WorkerJobPostingsResponse;
import com.wonday.farm.job.entity.JobPosting;
import com.wonday.farm.job.entity.WorkSchedule;
import com.wonday.farm.job.exception.WorkerJobPostingException;
import com.wonday.farm.job.repository.JobPostingRepository;
import com.wonday.farm.job.repository.WorkScheduleRepository;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.reference.AgriculturalCodeCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class WorkerJobPostingQueryService {
	private static final int CANDIDATE_BATCH_SIZE = 100;
	private final MemberRepository members;
	private final JobPostingRepository postings;
	private final WorkScheduleRepository schedules;
	private final AgriculturalCodeCatalog codes;
	private final Clock clock;

	public WorkerJobPostingQueryService(MemberRepository members, JobPostingRepository postings,
			WorkScheduleRepository schedules, AgriculturalCodeCatalog codes, @Qualifier("jobClock") Clock clock) {
		this.members = members;
		this.postings = postings;
		this.schedules = schedules;
		this.codes = codes;
		this.clock = clock;
	}

	public WorkerJobPostingsResponse list(long workerId, int page, int size) {
		requireWorker(workerId);
		if (page < 0 || size < 1 || size > 100) {
			throw new WorkerJobPostingException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
				"page는 0 이상, size는 1~100이어야 합니다.", page < 0 ? "page" : "size");
		}
		Instant now = clock.instant();
		// Java 시간대의 최대 오프셋 범위(-18시간)보다 오래된 시작은 후보에서 제외한다.
		// 초 단위로 여유를 두고, 최종 판정은 아래에서 실제 저장 시간대로 다시 한다.
		LocalDateTime earliestStart = LocalDateTime.ofInstant(
			now.minusSeconds(18 * 3600L + 1).truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
		long offset = (long) page * size;
		long matched = 0;
		Long beforeId = null;
		var results = new ArrayList<WorkerJobPostingsResponse.Posting>();
		while (results.size() <= size) {
			var candidates = postings.findRecruitmentCandidates(beforeId, WorkSchedule.Status.OPEN,
				earliestStart, PageRequest.of(0, CANDIDATE_BATCH_SIZE));
			if (candidates.isEmpty()) break;
			beforeId = candidates.getLast().getId();
			var ids = candidates.stream().map(JobPosting::getId).toList();
			var recruiting = schedules.findForWorkerPostings(ids).stream()
				.filter(schedule -> schedule.getStatus() == WorkSchedule.Status.OPEN
					&& startUnavailableReason(schedule, now) == null).toList();
			var grouped = recruiting.stream().collect(Collectors.groupingBy(schedule -> schedule.getJobPosting().getId()));
			Context context = context(recruiting, workerId);
			for (var posting : candidates) {
				var dates = grouped.getOrDefault(posting.getId(), List.of());
				if (dates.isEmpty()) continue;
				if (matched++ < offset) continue;
				results.add(summary(posting, dates, context, now));
				if (results.size() > size) break;
			}
			if (candidates.size() < CANDIDATE_BATCH_SIZE) break;
		}
		boolean hasNext = results.size() > size;
		return new WorkerJobPostingsResponse(List.copyOf(results.subList(0, Math.min(size, results.size()))), page, size, hasNext);
	}

	public WorkerJobPostingResponse detail(long workerId, long postingId) {
		requireWorker(workerId);
		if (postingId <= 0) {
			throw new WorkerJobPostingException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "공고 ID는 양수여야 합니다.", "jobPostingId");
		}
		var posting = postings.findById(postingId).orElseThrow(() ->
			new WorkerJobPostingException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "공고를 찾을 수 없습니다.", null));
		Instant now = clock.instant();
		var dates = schedules.findForWorkerPostings(List.of(postingId));
		Context context = context(dates, workerId);
		return new WorkerJobPostingResponse(posting.getId(), posting.getTitle(), posting.getDescription(), posting.getWorkLocation(),
			posting.getCropCode(), codes.cropName(posting.getCropCode()), posting.getWorkTypeCode(), codes.workTypeName(posting.getWorkTypeCode()),
			posting.getTimeZone(), posting.getHeadcount(), posting.getDailyWage(), dates.stream().map(schedule -> {
				long confirmed = context.counts().getOrDefault(schedule.getId(), 0L);
				long remaining = remainingSlots(posting, confirmed);
				String reason = unavailableReason(schedule, remaining, context.applications().get(schedule.getId()), now);
				return new WorkerJobPostingResponse.Schedule(schedule.getId(), schedule.getWorkDate(), schedule.getPlannedStartAt(),
					schedule.getPlannedEndAt(), schedule.getBreakStartAt(), schedule.getBreakEndAt(), schedule.getStatus(),
					schedule.getTermsVersion(), confirmed, remaining, reason == null, reason);
			}).toList());
	}

	private void requireWorker(long workerId) {
		members.findById(workerId).filter(member -> member.memberRole().orElse(null) == MemberRole.WORKER)
			.orElseThrow(() -> new AccessDeniedException("Worker role required"));
	}

	private Context context(List<WorkSchedule> dates, long workerId) {
		if (dates.isEmpty()) return new Context(Map.of(), Map.of());
		var ids = dates.stream().map(WorkSchedule::getId).toList();
		Map<Long, Long> counts = new HashMap<>();
		for (var count : schedules.countWorkerScheduleAssignments(ids)) {
			counts.put(count.getWorkScheduleId(), count.getConfirmedCount());
		}
		Map<Long, WorkScheduleRepository.WorkerApplication> applications = new HashMap<>();
		for (var application : schedules.findWorkerApplications(workerId, ids)) {
			applications.put(application.getWorkScheduleId(), application);
		}
		return new Context(counts, applications);
	}

	private WorkerJobPostingsResponse.Posting summary(JobPosting posting, List<WorkSchedule> dates, Context context, Instant now) {
		var available = dates.stream().map(schedule -> {
			long confirmed = context.counts().getOrDefault(schedule.getId(), 0L);
			long remaining = remainingSlots(posting, confirmed);
			String reason = unavailableReason(schedule, remaining, context.applications().get(schedule.getId()), now);
			return new WorkerJobPostingsResponse.RecruitingSchedule(schedule.getId(), schedule.getWorkDate(),
				confirmed, remaining, reason == null, reason);
		}).toList();
		return new WorkerJobPostingsResponse.Posting(posting.getId(), posting.getTitle(), posting.getWorkLocation(),
			posting.getCropCode(), codes.cropName(posting.getCropCode()), posting.getWorkTypeCode(), codes.workTypeName(posting.getWorkTypeCode()),
			posting.getHeadcount(), posting.getDailyWage(), dates.stream().map(WorkSchedule::getWorkDate).toList(), available);
	}

	private static long remainingSlots(JobPosting posting, long confirmed) {
		return Math.max((long) posting.getHeadcount() - confirmed, 0);
	}

	private static String unavailableReason(WorkSchedule schedule, long remaining,
			WorkScheduleRepository.WorkerApplication application, Instant now) {
		if (schedule.getStatus() == WorkSchedule.Status.PAUSED) return "RECRUITMENT_PAUSED";
		if (schedule.getStatus() == WorkSchedule.Status.CLOSED) return "RECRUITMENT_CLOSED";
		if (schedule.getStatus() == WorkSchedule.Status.REMOVED) return "RECRUITMENT_CANCELLED";
		if (schedule.getStatus() != WorkSchedule.Status.OPEN) return "SCHEDULE_STATUS_UNAVAILABLE";
		String timeReason = startUnavailableReason(schedule, now);
		if (timeReason != null) return timeReason;
		if (remaining == 0) return "CAPACITY_FULL";
		if (application == null) return null;
		// 재지원 정책: 시작 전 취소된 확정 근무와 미선정 이력은 허용하되, 실제 재지원 저장은 APP-02~03에서 구현한다.
		if (application.getAssignmentId() != null) {
			return "CANCELLED".equals(application.getChangeType()) ? null : "ALREADY_CONFIRMED";
		}
		if ("PENDING".equals(application.getApplicationStatus())) return "ALREADY_APPLIED";
		if ("SELECTED".equals(application.getApplicationStatus())) return "ALREADY_CONFIRMED";
		if ("NOT_SELECTED".equals(application.getApplicationStatus())) return null;
		return "APPLICATION_STATUS_UNAVAILABLE";
	}

	private static String startUnavailableReason(WorkSchedule schedule, Instant now) {
		String value = schedule.getJobPosting().getTimeZone();
		if (value == null || value.isBlank()) return "TIME_ZONE_UNAVAILABLE";
		try {
			var zone = ZoneId.of(value);
			var start = schedule.getPlannedStartAt();
			if (start == null) return "SCHEDULE_START_TIME_UNAVAILABLE";
			var offsets = zone.getRules().getValidOffsets(start);
			if (offsets.size() != 1) return "SCHEDULE_START_TIME_UNAVAILABLE";
			return now.isBefore(start.toInstant(offsets.getFirst())) ? null : "SCHEDULE_STARTED";
		} catch (DateTimeException exception) {
			return "TIME_ZONE_UNAVAILABLE";
		}
	}

	private record Context(Map<Long, Long> counts, Map<Long, WorkScheduleRepository.WorkerApplication> applications) {}
}

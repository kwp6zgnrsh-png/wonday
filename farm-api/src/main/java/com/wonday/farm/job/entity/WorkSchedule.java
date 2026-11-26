package com.wonday.farm.job.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import com.wonday.farm.job.dto.CreateJobPostingRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "work_schedule", uniqueConstraints = @UniqueConstraint(
	name = "uq_posting_work_date", columnNames = {"job_posting_id", "work_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkSchedule {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "job_posting_id", nullable = false)
	private JobPosting jobPosting;

	@Column(name = "work_date", nullable = false)
	private LocalDate workDate;

	@Column(name = "planned_start_at", nullable = false)
	private LocalDateTime plannedStartAt;

	@Column(name = "planned_end_at", nullable = false)
	private LocalDateTime plannedEndAt;

	@Column(name = "break_start_at")
	private LocalDateTime breakStartAt;

	@Column(name = "break_end_at")
	private LocalDateTime breakEndAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private Status status;

	@Column(name = "terms_version", nullable = false)
	private int termsVersion;

	// 기존 컬럼을 사용한다. JOB-07에서 새로 기록하는 모집 취소 시각은 UTC로 보관한다.
	@Column(name = "cancelled_at")
	private LocalDateTime cancelledAt;

	public WorkSchedule(JobPosting posting, CreateJobPostingRequest.Schedule schedule) {
		jobPosting = posting;
		workDate = schedule.workDate();
		plannedStartAt = schedule.plannedStartAt();
		plannedEndAt = schedule.plannedEndAt();
		breakStartAt = schedule.breakStartAt();
		breakEndAt = schedule.breakEndAt();
		status = Status.OPEN;
		termsVersion = 1;
	}

	public enum Status {
		OPEN, PAUSED, CLOSED, REMOVED
	}

	public boolean hasSameConditions(CreateJobPostingRequest.Schedule conditions) {
		return Objects.equals(workDate, conditions.workDate())
			&& Objects.equals(plannedStartAt, conditions.plannedStartAt())
			&& Objects.equals(plannedEndAt, conditions.plannedEndAt())
			&& Objects.equals(breakStartAt, conditions.breakStartAt())
			&& Objects.equals(breakEndAt, conditions.breakEndAt());
	}

	// 권한·확정 여부·입력 검증을 마친 서비스가 호출한다. 일정 ID와 모집 상태는 유지한다.
	public void updateConditions(CreateJobPostingRequest.Schedule conditions) {
		int nextVersion = Math.incrementExact(termsVersion);
		workDate = conditions.workDate();
		plannedStartAt = conditions.plannedStartAt();
		plannedEndAt = conditions.plannedEndAt();
		breakStartAt = conditions.breakStartAt();
		breakEndAt = conditions.breakEndAt();
		termsVersion = nextVersion;
	}

	// 서비스의 권한·시작 시각·확정자 검사를 통과한 뒤 호출한다.
	// 조건과 지원 이력을 지우지 않으며, 재요청이 와도 최초 취소 시각을 덮어쓰지 않는다.
	public void cancelRecruitment(LocalDateTime cancelledAtUtc) {
		if (status == Status.REMOVED) return;
		status = Status.REMOVED;
		cancelledAt = cancelledAtUtc;
	}
}

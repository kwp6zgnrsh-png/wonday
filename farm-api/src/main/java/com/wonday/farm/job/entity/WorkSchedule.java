package com.wonday.farm.job.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
		OPEN, CLOSED, REMOVED
	}
}

package com.wonday.farm.job.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
import org.hibernate.annotations.Immutable;

// 모집 조건의 과거 버전이다. 취소·조기 종료 이력이나 확정 근무의 약정과는 별도로 보관한다.
@Entity
@Table(name = "work_schedule_revision", uniqueConstraints = @UniqueConstraint(
	name = "uq_schedule_revision_version", columnNames = {"work_schedule_id", "terms_version"}))
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkScheduleRevision {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "work_schedule_id", nullable = false)
	private WorkSchedule workSchedule;
	@Column(name = "terms_version", nullable = false)
	private int termsVersion;
	@Enumerated(EnumType.STRING) @Column(name = "revision_type", nullable = false, length = 32)
	private Type revisionType;
	@Column(name = "actor_id")
	private Long actorId;
	@Column(name = "recorded_at_utc", nullable = false)
	private LocalDateTime recordedAtUtc;
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
	@Column(name = "time_zone", length = 64)
	private String timeZone;
	@Column(name = "work_location", nullable = false, length = 500)
	private String workLocation;
	@Column(name = "crop_code", nullable = false, length = 40)
	private String cropCode;
	@Column(name = "work_type_code", nullable = false, length = 40)
	private String workTypeCode;
	@Column(nullable = false)
	private int headcount;
	@Column(name = "daily_wage", nullable = false)
	private long dailyWage;

	private WorkScheduleRevision(WorkSchedule schedule, Type type, Long actorId, LocalDateTime now) {
		workSchedule = schedule;
		termsVersion = schedule.getTermsVersion();
		revisionType = type;
		this.actorId = actorId;
		recordedAtUtc = now;
		workDate = schedule.getWorkDate();
		plannedStartAt = schedule.getPlannedStartAt();
		plannedEndAt = schedule.getPlannedEndAt();
		breakStartAt = schedule.getBreakStartAt();
		breakEndAt = schedule.getBreakEndAt();
		var posting = schedule.getJobPosting();
		timeZone = posting.getTimeZone();
		workLocation = posting.getWorkLocation();
		cropCode = posting.getCropCode();
		workTypeCode = posting.getWorkTypeCode();
		headcount = posting.getHeadcount();
		dailyWage = posting.getDailyWage();
	}

	// 과거의 실제 변경자·변경 시각을 알 수 없어 현재 관측한 상태라는 사실을 명시한다.
	public static WorkScheduleRevision baseline(WorkSchedule schedule, LocalDateTime now) {
		return new WorkScheduleRevision(schedule, Type.BASELINE, null, now);
	}

	public static WorkScheduleRevision updated(WorkSchedule schedule, long actorId, LocalDateTime now) {
		return new WorkScheduleRevision(schedule, Type.UPDATED, actorId, now);
	}

	public enum Type { BASELINE, UPDATED }
}

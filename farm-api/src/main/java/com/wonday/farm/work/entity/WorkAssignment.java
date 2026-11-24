package com.wonday.farm.work.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 공고의 현재 값으로 대체하지 않고, 확정할 때 복사한 약정만 읽는다.
@Entity
@Table(name = "work_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkAssignment {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "application_id", nullable = false, unique = true)
	private long applicationId;
	@Column(name = "confirmed_at_utc")
	private LocalDateTime confirmedAtUtc;
	@Column(name = "agreed_work_date")
	private LocalDate agreedWorkDate;
	@Column(name = "agreed_time_zone", length = 64)
	private String agreedTimeZone;
	@Column(name = "agreed_work_location", nullable = false, length = 500)
	private String agreedWorkLocation;
	@Column(name = "agreed_crop_code", nullable = false, length = 40)
	private String agreedCropCode;
	@Column(name = "agreed_work_type_code", nullable = false, length = 40)
	private String agreedWorkTypeCode;
	@Column(name = "agreed_start_at", nullable = false)
	private LocalDateTime agreedStartAt;
	@Column(name = "agreed_end_at", nullable = false)
	private LocalDateTime agreedEndAt;
	@Column(name = "agreed_break_start_at")
	private LocalDateTime agreedBreakStartAt;
	@Column(name = "agreed_break_end_at")
	private LocalDateTime agreedBreakEndAt;
	@Column(name = "agreed_daily_wage", nullable = false)
	private long agreedDailyWage;
}

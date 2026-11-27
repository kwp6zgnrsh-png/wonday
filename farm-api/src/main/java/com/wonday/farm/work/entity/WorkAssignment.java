package com.wonday.farm.work.entity;

import java.math.BigDecimal;
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
	// 완료 처리는 최초 한 번만 기록한다. 종료 시각 경과나 급여 지급 여부로 채우지 않는다.
	@Column(name = "completed_at_utc")
	private LocalDateTime completedAtUtc;
	@Column(name = "completed_by")
	private Long completedBy;
	@Column(name = "agreed_work_date")
	private LocalDate agreedWorkDate;
	@Column(name = "agreed_time_zone", length = 64)
	private String agreedTimeZone;
	@Column(name = "agreed_work_location", nullable = false, length = 500)
	private String agreedWorkLocation;
	// 확정 당시 저장된 좌표만 읽는다. 현재 공고 좌표로 과거 약정을 보완하지 않는다.
	@Column(name = "agreed_latitude", precision = 9, scale = 6)
	private BigDecimal agreedLatitude;
	@Column(name = "agreed_longitude", precision = 9, scale = 6)
	private BigDecimal agreedLongitude;
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

	// 취소·정정용 메서드는 제공하지 않는다. 재요청은 서비스에서 최초 결과를 반환한다.
	public void complete(LocalDateTime completedAtUtc, long employerId) {
		if (this.completedAtUtc != null || completedBy != null) {
			throw new IllegalStateException("Completion already recorded");
		}
		if (completedAtUtc == null || employerId <= 0) throw new IllegalArgumentException("Invalid completion");
		this.completedAtUtc = completedAtUtc;
		completedBy = employerId;
	}
}

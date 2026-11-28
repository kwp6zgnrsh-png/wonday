package com.wonday.farm.application.entity;

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

@Entity
@Table(name = "application")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobApplication {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "work_schedule_id", nullable = false)
	private long workScheduleId;
	@Column(name = "worker_id", nullable = false)
	private long workerId;
	@Column(nullable = false, length = 32)
	private String status;
	// 기존 컬럼의 과거 값은 보존한다. 이번 API의 새 행은 두 접수 시각 모두 UTC다.
	@Column(name = "applied_at", nullable = false)
	private LocalDateTime appliedAt;
	@Column(name = "applied_at_utc")
	private LocalDateTime appliedAtUtc;
	@Column(name = "application_round", nullable = false)
	private int applicationRound;
	// 기존 지원의 접수 당시 버전은 추측하지 않으므로 NULL을 허용한다.
	@Column(name = "applied_terms_version")
	private Integer appliedTermsVersion;
	@Column(name = "is_current", nullable = false)
	private boolean current;

	public JobApplication(long workerId, long scheduleId, int round, int termsVersion, LocalDateTime nowUtc) {
		if (workerId <= 0 || scheduleId <= 0 || round <= 0 || termsVersion <= 0 || nowUtc == null) {
			throw new IllegalArgumentException("Invalid application");
		}
		this.workerId = workerId;
		workScheduleId = scheduleId;
		applicationRound = round;
		appliedTermsVersion = termsVersion;
		appliedAt = nowUtc;
		appliedAtUtc = nowUtc;
		status = "PENDING";
		current = true;
	}


    // 선정만 바꾼다. 최초 지원 버전·시각·회차는 재확인이나 선정으로 덮어쓰지 않는다.
    public void select() {
        if (!current || !"PENDING".equals(status)) throw new IllegalStateException("Application is not pending");
        status = "SELECTED";
    }

	// 과거 상태·시각·약정 연결은 보존하고 현재 회차 표시만 해제한다.
	public void retireForReapplication() { current = false; }
}
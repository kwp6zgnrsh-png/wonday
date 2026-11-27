package com.wonday.farm.application.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

// 성공 기록이 없는 첫 요청도 잠글 수 있는 근로자별 직렬화 행이다.
@Entity
@Table(name = "application_request_lock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationRequestLock {
	@Id @Column(name = "worker_id")
	private Long workerId;
}
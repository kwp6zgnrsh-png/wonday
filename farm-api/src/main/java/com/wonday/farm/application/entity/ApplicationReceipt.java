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

// 성공 요청만 지원과 같은 트랜잭션으로 저장한다. 자동 만료·삭제는 하지 않는다.
@Entity
@Table(name = "application_receipt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationReceipt {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "worker_id", nullable = false, updatable = false)
	private long workerId;
	@Column(name = "request_key", nullable = false, updatable = false, length = 36, columnDefinition = "char(36)")
	private String requestKey;
	@Column(name = "job_posting_id", nullable = false, updatable = false)
	private long jobPostingId;
	@Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64, columnDefinition = "char(64)")
	private String requestFingerprint;
	@Column(name = "response_payload", nullable = false, updatable = false, columnDefinition = "TEXT")
	private String responsePayload;
	@Column(name = "created_at_utc", nullable = false, updatable = false)
	private LocalDateTime createdAtUtc;

	public ApplicationReceipt(long workerId, String requestKey, long postingId, String fingerprint,
			String responsePayload, LocalDateTime nowUtc) {
		this.workerId = workerId;
		this.requestKey = requestKey;
		jobPostingId = postingId;
		requestFingerprint = fingerprint;
		this.responsePayload = responsePayload;
		createdAtUtc = nowUtc;
	}
}
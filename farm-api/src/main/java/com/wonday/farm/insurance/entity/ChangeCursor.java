package com.wonday.farm.insurance.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "insurance_change_cursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeCursor {
	@Id @Column(length = 36)
	private String token;
	@Column(name = "worker_id", nullable = false)
	private long workerId;
	@Column(name = "after_sequence", nullable = false)
	private long afterSequence;

	public ChangeCursor(long workerId, long sequence) {
		token = UUID.randomUUID().toString();
		this.workerId = workerId;
		afterSequence = sequence;
	}
}

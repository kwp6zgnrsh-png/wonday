package com.wonday.farm.insurance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "insurance_change_stream")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeStream {
	@Id @Column(name = "worker_id")
	private Long workerId;
	@Column(name = "last_sequence", nullable = false)
	private long lastSequence;

	public long nextSequence() {
		lastSequence = Math.incrementExact(lastSequence);
		return lastSequence;
	}
}

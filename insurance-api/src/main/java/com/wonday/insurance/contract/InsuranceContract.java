package com.wonday.insurance.contract;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "insurance_contract")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class InsuranceContract {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "member_id", nullable = false)
	private long memberId;

	@Column(name = "product_version_id", nullable = false)
	private long productVersionId;

	@Column(name = "accepted_at", nullable = false)
	private LocalDateTime acceptedAt;

	@Column(name = "coverage_from", nullable = false)
	private LocalDateTime coverageFrom;

	@Column(name = "coverage_to", nullable = false)
	private LocalDateTime coverageTo;

	@Column(nullable = false, length = 32)
	private String status;

	InsuranceContract(long memberId, long productVersionId, LocalDateTime acceptedAt,
		LocalDateTime coverageFrom, LocalDateTime coverageTo) {
		this.memberId = memberId;
		this.productVersionId = productVersionId;
		this.acceptedAt = acceptedAt;
		this.coverageFrom = coverageFrom;
		this.coverageTo = coverageTo;
		this.status = "ACTIVE";
	}
}

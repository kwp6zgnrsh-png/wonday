package com.wonday.insurance.contract;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import com.wonday.insurance.api.ApiTime;

public record ContractView(long id, long productVersionId, String productName, int versionNo,
	OffsetDateTime acceptedAt, OffsetDateTime coverageFrom, OffsetDateTime coverageTo,
	String status, String termsText) {
	// JPA 프로젝션은 DB의 UTC LocalDateTime을 전달하며, 응답 생성 경계에서만 변환한다.
	public ContractView(long id, long productVersionId, String productName, int versionNo,
		LocalDateTime acceptedAt, LocalDateTime coverageFrom, LocalDateTime coverageTo, String status, String termsText) {
		this(id, productVersionId, productName, versionNo, ApiTime.fromUtc(acceptedAt), ApiTime.fromUtc(coverageFrom),
			ApiTime.fromUtc(coverageTo), status, termsText);
	}
}

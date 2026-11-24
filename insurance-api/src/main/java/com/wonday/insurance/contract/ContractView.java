package com.wonday.insurance.contract;

import java.time.LocalDateTime;

public record ContractView(long id, long productVersionId, String productName, int versionNo,
	LocalDateTime acceptedAt, LocalDateTime coverageFrom, LocalDateTime coverageTo,
	String status, String termsText) {}

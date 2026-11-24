package com.wonday.insurance.contract;

import com.wonday.insurance.platform.WorkerIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ContractEnrollmentRequest(
	@Positive long productVersionId,
	@AssertTrue boolean termsAccepted,
	@AssertTrue boolean platformConsentAccepted,
	@NotNull @Valid WorkerIdentity identity
) {}

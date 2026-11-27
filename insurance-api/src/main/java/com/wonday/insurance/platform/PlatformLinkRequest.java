package com.wonday.insurance.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record PlatformLinkRequest(
	@AssertTrue boolean platformConsentAccepted,
	@NotBlank @Size(max = 64) String consentVersion,
	@NotNull @Valid WorkerIdentity identity
) {}

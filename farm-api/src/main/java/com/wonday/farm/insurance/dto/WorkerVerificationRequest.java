package com.wonday.farm.insurance.dto;

import com.wonday.farm.insurance.serialization.IdentityStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record WorkerVerificationRequest(
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String name,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String phone,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String residentRegistrationNumber
) {
	public WorkerVerificationRequest {
		name = name == null ? null : name.strip();
		phone = normalizeNumber(phone);
		residentRegistrationNumber = normalizeNumber(residentRegistrationNumber);
	}

	private static String normalizeNumber(String value) {
		return value == null ? null : value.replaceAll("(?U)[\\s-]", "");
	}

	@Override
	public String toString() { return "WorkerVerificationRequest[redacted]"; }
}

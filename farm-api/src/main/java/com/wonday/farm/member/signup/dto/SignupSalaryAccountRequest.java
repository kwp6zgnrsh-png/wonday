package com.wonday.farm.member.signup.dto;

import com.wonday.farm.member.signup.serialization.SignupStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SignupSalaryAccountRequest(
	@NotBlank @Size(max = 3) @JsonDeserialize(using = SignupStringDeserializer.class) String bankCode,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String accountNumber,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String accountHolderName
) {
	@Override public String toString() { return "SignupSalaryAccountRequest[redacted]"; }
}

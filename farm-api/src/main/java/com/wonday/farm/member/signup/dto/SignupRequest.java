package com.wonday.farm.member.signup.dto;

import com.wonday.farm.member.signup.serialization.SignupStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

public record SignupRequest(
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String loginId,
	@NotBlank @Size(max = 72) @JsonDeserialize(using = SignupStringDeserializer.class) String password,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String name,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String phone,
	@NotBlank @Size(max = 32) @JsonDeserialize(using = SignupStringDeserializer.class) String role,
	@NotBlank @Size(max = 16) @JsonDeserialize(using = SignupStringDeserializer.class) String nationalityType,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SignupStringDeserializer.class) String identityNumber,
	@Valid SignupSalaryAccountRequest salaryAccount
) {
	// record의 기본 출력으로 비밀번호·번호 원문이 로그에 실리지 않도록 한다.
	@Override public String toString() { return "SignupRequest[redacted]"; }
}

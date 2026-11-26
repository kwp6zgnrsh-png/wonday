package com.wonday.farm.insurance.dto;

import com.wonday.farm.insurance.serialization.IdentityStringDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// V1 요청을 바꾸지 않고 내외국인 구분과 공통 식별번호를 명시하는 V2 요청이다.
// 숫자로 받으면 앞자리 0이 사라질 수 있어 모든 입력을 JSON 문자열로 제한한다.
public record WorkerVerificationV2Request(
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String name,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String phone,
	@NotBlank @Size(max = 16) @Pattern(regexp = "DOMESTIC|FOREIGN")
	@JsonDeserialize(using = IdentityStringDeserializer.class) String nationalityType,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = IdentityStringDeserializer.class) String identityNumber
) {
	@Override public String toString() { return "WorkerVerificationV2Request[redacted]"; }
}

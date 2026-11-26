package com.wonday.farm.salary.dto;

import com.wonday.farm.salary.serialization.SalaryAccountStringDeserializer;
import com.wonday.farm.salary.serialization.SalaryAccountVersionDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// 조회 응답의 버전과 새 계좌 전체를 보낸다. 마스킹된 번호를 다시 저장하는 요청은 허용하지 않는다.
public record SalaryAccountRequest(
	@NotBlank @Size(max = 3) @JsonDeserialize(using = SalaryAccountStringDeserializer.class) String bankCode,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SalaryAccountStringDeserializer.class) String accountNumber,
	@NotBlank @Size(max = 100) @JsonDeserialize(using = SalaryAccountStringDeserializer.class) String accountHolderName,
	@NotNull @PositiveOrZero @JsonDeserialize(using = SalaryAccountVersionDeserializer.class) Long version
) {
	@Override public String toString() { return "SalaryAccountRequest[redacted]"; }
}

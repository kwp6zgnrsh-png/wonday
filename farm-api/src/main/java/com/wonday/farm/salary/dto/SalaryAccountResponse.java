package com.wonday.farm.salary.dto;

// 미등록 상태에도 version은 제공한다. 번호 원문·암호문·회원 식별번호는 응답하지 않는다.
public record SalaryAccountResponse(boolean registered, String bankCode, String maskedAccountNumber,
		String accountHolderName, long version) {
	@Override public String toString() { return "SalaryAccountResponse[redacted]"; }
}

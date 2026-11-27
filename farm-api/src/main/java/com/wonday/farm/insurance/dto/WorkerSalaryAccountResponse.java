package com.wonday.farm.insurance.dto;

// 보험 서버에는 계좌 등록 여부·은행 코드만 제공한다. 번호·마스킹 번호·예금주·버전은 포함하지 않는다.
public record WorkerSalaryAccountResponse(long workerId, boolean registered, String bankCode) {
	@Override public String toString() { return "WorkerSalaryAccountResponse[redacted]"; }
}

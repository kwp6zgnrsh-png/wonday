package com.wonday.farm.salary.validation;

// 계좌 실재 여부를 조회하지 않고 입력 형식만 검사한다. 은행별 하이픈 묶음 형식은 추정하지 않는다.
public final class SalaryAccountValidator {
	private SalaryAccountValidator() {}

	public static NormalizedAccount normalize(String bankCode, String accountNumber, String accountHolderName) {
		if (bankCode == null || !bankCode.matches("[0-9]{3}")
				|| accountNumber == null || !accountNumber.matches("[0-9-]{1,100}")
				|| accountHolderName == null || accountHolderName.length() > 100) {
			throw invalid();
		}
		String number = accountNumber.replace("-", "");
		String holder = accountHolderName.strip();
		if (!number.matches("[0-9]{1,30}") || holder.isBlank()) throw invalid();
		return new NormalizedAccount(bankCode, number, holder);
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("급여 계좌 입력 형식을 확인해 주세요.");
	}

	public record NormalizedAccount(String bankCode, String accountNumber, String accountHolderName) {
		@Override public String toString() { return "NormalizedAccount[redacted]"; }
	}
}

package com.wonday.farm.salary.crypto;

// 키·번호·원문 예외를 응답이나 로그에 붙이지 않는다.
public class SalaryAccountCryptoUnavailableException extends RuntimeException {
	public SalaryAccountCryptoUnavailableException() {
		super("급여 계좌 암호화 처리를 사용할 수 없습니다.");
	}
}

package com.wonday.farm.member.identity;

// 키나 암호문 원문을 예외 메시지·원인 체인에 넣지 않는다.
public class IdentityCryptoUnavailableException extends RuntimeException {
	public IdentityCryptoUnavailableException() {
		super("식별정보 암호화 설정 또는 저장 상태를 확인할 수 없습니다.");
	}
}
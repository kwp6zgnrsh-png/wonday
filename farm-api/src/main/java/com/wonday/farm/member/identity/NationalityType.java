package com.wonday.farm.member.identity;

public enum NationalityType {
	DOMESTIC, FOREIGN;

	// 식별번호 종류는 요청에서 따로 선택하지 않고 내외국인 구분에 맞춰 서버가 정한다.
	public IdentityType identityType() {
		return this == DOMESTIC ? IdentityType.RESIDENT_REGISTRATION_NUMBER : IdentityType.FOREIGN_REGISTRATION_NUMBER;
	}
}
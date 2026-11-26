package com.wonday.insurance.member;

import com.fasterxml.jackson.annotation.JsonCreator;

// 국적 구분은 WORKER/ADMIN 권한과 독립적이다. 외국인에게 별도의 회원 테이블이나 권한을 부여하지 않는다.
public enum NationalityType {

	DOMESTIC,
	FOREIGN;

	@JsonCreator
	public static NationalityType fromJson(String value) {
		// 숫자 enum 순번이나 화면의 한글 표시값을 API 코드로 암묵 변환하지 않는다.
		return valueOf(value);
	}
}

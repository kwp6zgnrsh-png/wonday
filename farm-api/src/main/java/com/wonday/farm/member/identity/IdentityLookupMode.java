package com.wonday.farm.member.identity;

// 데이터 확인·이관 완료 전에는 기존 조회를 유지한다. 전환은 운영자가 명시적으로 수행한다.
public enum IdentityLookupMode {
	LEGACY, ENCRYPTED
}
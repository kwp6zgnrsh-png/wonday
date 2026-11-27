package com.wonday.insurance.member.migration;

/** 원문 번호·암호문·키·DB 예외 원인을 포함하지 않는 이관 전용 오류다. */
public final class MemberIdentityMigrationException extends RuntimeException {
	public MemberIdentityMigrationException(String message) { super(message); }
}

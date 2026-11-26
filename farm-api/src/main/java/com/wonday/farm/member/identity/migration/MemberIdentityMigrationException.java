package com.wonday.farm.member.identity.migration;

// 원문·암호문·HMAC·키·SQL 예외를 원인 체인에 연결하지 않는다.
public class MemberIdentityMigrationException extends RuntimeException {
	public MemberIdentityMigrationException(String safeMessage) { super(safeMessage); }
}
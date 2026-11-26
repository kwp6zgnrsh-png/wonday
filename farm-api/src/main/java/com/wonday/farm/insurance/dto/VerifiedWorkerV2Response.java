package com.wonday.farm.insurance.dto;

import com.wonday.farm.member.identity.NationalityType;

// 저장된 근로자의 기본 정보만 반환한다. 식별번호·종류·암호문·HMAC·대조 결과는 포함하지 않는다.
public record VerifiedWorkerV2Response(long workerId, String name, String phone, NationalityType nationalityType) {
	@Override public String toString() { return "VerifiedWorkerV2Response[redacted]"; }
}

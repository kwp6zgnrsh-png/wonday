package com.wonday.farm.member.identity;

// 평문을 담지 않는 서버 내부 저장 결과다. 로그에 암호문·HMAC도 출력하지 않는다.
public record StoredIdentity(long memberId, NationalityType nationalityType, IdentityType identityType,
		String ciphertext, String hmac) {
	public StoredIdentity {
		if (memberId <= 0 || nationalityType == null || identityType != nationalityType.identityType()
				|| ciphertext == null || !ciphertext.startsWith("v1:") || ciphertext.length() > 256
				|| hmac == null || !hmac.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("식별정보 저장 형식을 확인해 주세요.");
		}
	}

	@Override
	public String toString() { return "StoredIdentity[redacted]"; }
}
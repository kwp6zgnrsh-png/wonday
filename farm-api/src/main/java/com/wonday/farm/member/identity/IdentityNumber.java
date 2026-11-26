package com.wonday.farm.member.identity;

// 주민번호·외국인등록번호의 형식만 정규화한다. 정부기관 진위 인증이나 국적 추정은 하지 않는다.
public final class IdentityNumber {
	private IdentityNumber() {}

	public static String normalize(String raw) {
		if (raw == null || raw.length() > 100) throw invalid();
		String normalized = raw.replaceAll("(?U)[\\s-]", "");
		if (!normalized.matches("[0-9]{13}")) throw invalid();
		return normalized;
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("식별번호 입력 형식을 확인해 주세요.");
	}
}
package com.wonday.farm.work.validation;

// 회원의 최신 번호만 사용한다. 형식이 잘못된 과거 번호나 미등록 번호를 가상 값으로 바꾸지 않는다.
public final class ApplicantPhone {
	private ApplicantPhone() {}
	public static String normalized(String value) {
		if (value == null) return null;
		String digits = value.replaceAll("(?U)[\\s-]", "");
		return digits.matches("[0-9]{10,11}") ? digits : null;
	}
	public static String state(String value) {
		if (value == null || value.replaceAll("(?U)[\\s-]", "").isEmpty()) return "NOT_REGISTERED";
		return normalized(value) == null ? "INVALID_FORMAT" : "AVAILABLE";
	}
}

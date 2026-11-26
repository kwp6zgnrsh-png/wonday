package com.wonday.insurance.member;

public enum IdentityType {

	RESIDENT_REGISTRATION_NUMBER,
	FOREIGN_REGISTRATION_NUMBER;

	public static IdentityType forNationality(NationalityType nationality) {
		return switch (nationality) {
			case DOMESTIC -> RESIDENT_REGISTRATION_NUMBER;
			case FOREIGN -> FOREIGN_REGISTRATION_NUMBER;
		};
	}
}

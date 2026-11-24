package com.wonday.insurance.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record WorkerIdentity(String name, String phone, String residentRegistrationNumber) {

	public WorkerIdentity {
		name = name == null ? "" : name.strip();
		phone = normalize(phone);
		residentRegistrationNumber = normalize(residentRegistrationNumber);
		if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)
			|| !phone.matches("[0-9]{10,11}") || !residentRegistrationNumber.matches("[0-9]{13}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid worker identity format");
		}
	}

	private static String normalize(String value) {
		return value == null ? "" : value.replace("-", "").replace(" ", "");
	}

	@Override
	public String toString() {
		return "WorkerIdentity[redacted]";
	}
}

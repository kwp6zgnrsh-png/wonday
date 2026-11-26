package com.wonday.insurance.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.wonday.insurance.member.IdentityType;
import com.wonday.insurance.member.NationalityType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

public record WorkerIdentity(String name, String phone, NationalityType nationalityType, String identityNumber) {

	public WorkerIdentity {
		name = name == null ? "" : name.strip();
		phone = normalize(phone);
		identityNumber = normalize(identityNumber);
		if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)
			|| nationalityType == null || !phone.matches("[0-9]{10,11}") || !identityNumber.matches("[0-9]{13}")) {
			throw invalid();
		}
	}

	public WorkerIdentity(String name, String phone, String residentRegistrationNumber) {
		this(name, phone, NationalityType.DOMESTIC, residentRegistrationNumber);
	}

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static WorkerIdentity fromJson(JsonNode input) {
		if (input == null || !input.isObject()) throw invalid();
		boolean legacy = input.has("residentRegistrationNumber");
		// 두 번호 필드를 동시에 허용하면 어느 번호를 확인했는지 불분명하므로 null이어도 중복은 거부한다.
		if (legacy && input.has("identityNumber")) throw invalid();
		NationalityType nationality;
		if (legacy && !input.has("nationalityType")) {
			nationality = NationalityType.DOMESTIC;
		} else {
			try {
				nationality = NationalityType.valueOf(text(input, "nationalityType"));
			} catch (IllegalArgumentException exception) {
				throw invalid();
			}
		}
		if (legacy && nationality != NationalityType.DOMESTIC) throw invalid();
		return new WorkerIdentity(text(input, "name"), text(input, "phone"), nationality,
			text(input, legacy ? "residentRegistrationNumber" : "identityNumber"));
	}

	public IdentityType identityType() {
		return IdentityType.forNationality(nationalityType);
	}

	private static String text(JsonNode input, String field) {
		JsonNode value = input.get(field);
		// 식별번호·전화번호를 JSON 숫자로 받으면 앞자리 0이나 큰 정수의 정밀도를 잃을 수 있다.
		if (value == null || !value.isString()) throw invalid();
		return value.stringValue();
	}

	private static String normalize(String value) {
		return value == null ? "" : value.replaceAll("(?U)[\\s-]", "");
	}

	private static ResponseStatusException invalid() {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid worker identity format");
	}

	@Override
	public String toString() {
		return "WorkerIdentity[redacted]";
	}
}

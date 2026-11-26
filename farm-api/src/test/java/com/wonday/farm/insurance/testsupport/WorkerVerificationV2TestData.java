package com.wonday.farm.insurance.testsupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import tools.jackson.databind.json.JsonMapper;

// 보험 V2 근로자 확인 검증용 가상 데이터. 실제 개인정보·키·토큰이 아니다.
public final class WorkerVerificationV2TestData {
	public static final JsonMapper JSON = JsonMapper.builder().build();
	public static final String NAME = "TEST WORKER";
	public static final String PHONE = "010-1234-5678";
	public static final String PHONE_NORMALIZED = "01012345678";
	// 가상 외국인 번호 (문서 예시). 앞자리 0 보존 검사는 ZERO_NUMBER를 쓴다.
	public static final String FOREIGN_NUMBER = "900101-5000000";
	public static final String FOREIGN_NUMBER_NORMALIZED = "9001015000000";
	public static final String DOMESTIC_NUMBER = "111111-1111111";
	public static final String DOMESTIC_NUMBER_NORMALIZED = "1111111111111";
	public static final String ZERO_NUMBER = "000101-0000000";
	public static final String ZERO_NUMBER_NORMALIZED = "0001010000000";
	// 응답·로그에 나오면 안 되는 문자열
	public static final List<String> SECRETS = List.of("900101-5000000", "9001015000000", "111111-1111111",
		"1111111111111", "0001010000000", "identityNumber", "identityType", "FOREIGN_REGISTRATION_NUMBER",
		"RESIDENT_REGISTRATION_NUMBER", "hmac", "Hmac", "ciphertext", "v1:", "SQL", "select ", "Exception", "jdbc",
		"test-service-token");

	private WorkerVerificationV2TestData() {}

	public static Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", NAME);
		body.put("phone", PHONE);
		body.put("nationalityType", "FOREIGN");
		body.put("identityNumber", FOREIGN_NUMBER);
		return body;
	}

	public static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

	public static WorkerVerificationV2Request request(String name, String phone, String nationality, String number) {
		return new WorkerVerificationV2Request(name, phone, nationality, number);
	}

	public static WorkerVerificationV2Request foreign() {
		return request(NAME, PHONE, "FOREIGN", FOREIGN_NUMBER);
	}

	public static WorkerVerificationV2Request domestic() {
		return request(NAME, PHONE, "DOMESTIC", DOMESTIC_NUMBER);
	}

	public static WorkerVerificationRepository.V2Match match(long id, String name, String phone, String nationality) {
		return new WorkerVerificationRepository.V2Match() {
			@Override public long getWorkerId() { return id; }
			@Override public String getName() { return name; }
			@Override public String getPhone() { return phone; }
			@Override public String getNationalityType() { return nationality; }
		};
	}
}

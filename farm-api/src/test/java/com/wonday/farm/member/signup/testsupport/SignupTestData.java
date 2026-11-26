package com.wonday.farm.member.signup.testsupport;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.wonday.farm.member.signup.dto.SignupRequest;
import com.wonday.farm.member.signup.dto.SignupSalaryAccountRequest;
import tools.jackson.databind.json.JsonMapper;

// 회원가입 단위 테스트용 가상 값. 실제 번호·계좌·키·.env 값이 아니다.
public final class SignupTestData {
	public static final String IDENTITY = "111111-1111111";
	public static final String IDENTITY_NORMALIZED = "1111111111111";
	public static final String ACCOUNT = "1002-0030-0400";
	public static final String ACCOUNT_NORMALIZED = "100200300400";
	public static final String ACCOUNT_KEY = key((byte) 0x55);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private SignupTestData() {}

	public static String key(byte fill) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, fill);
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static SignupRequest request(String role, String nationality, SignupSalaryAccountRequest account) {
		return new SignupRequest("worker-01", "Pass word!1", "  TEST WORKER  ", "010-1234-5678", role, nationality, IDENTITY, account);
	}

	public static SignupSalaryAccountRequest account() {
		return new SignupSalaryAccountRequest("020", ACCOUNT, "  TEST WORKER  ");
	}

	// 값 하나만 바꾸거나 지울 수 있도록 Map 본문을 만든다. 계좌는 기본 포함.
	public static Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("loginId", "worker-01");
		body.put("password", "Pass word!1");
		body.put("name", "TEST WORKER");
		body.put("phone", "010-1234-5678");
		body.put("role", "WORKER");
		body.put("nationalityType", "FOREIGN");
		body.put("identityNumber", IDENTITY);
		Map<String, Object> account = new LinkedHashMap<>();
		account.put("bankCode", "020");
		account.put("accountNumber", ACCOUNT);
		account.put("accountHolderName", "TEST WORKER");
		body.put("salaryAccount", account);
		return body;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> account(Map<String, Object> body) {
		return (Map<String, Object>) body.get("salaryAccount");
	}

	public static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

	// 테스트 전용 복호화. 운영 코드에는 계좌 복호화 API가 없으므로 운영 AAD 형식을 재현해 원문·결합 여부만 확인한다.
	public static String decryptAccount(String ciphertext, long memberId, String bankCode, String key) throws Exception {
		byte[] packed = Base64.getUrlDecoder().decode(ciphertext.substring(3));
		javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(key), "AES"),
			new javax.crypto.spec.GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
		cipher.updateAAD(("farm/member/salary-account/v1:" + memberId + ":" + bankCode)
			.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		return new String(cipher.doFinal(packed, 12, packed.length - 12), java.nio.charset.StandardCharsets.US_ASCII);
	}
}

package com.wonday.farm.salary.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.wonday.farm.member.Member;
import com.wonday.farm.member.MemberRole;
import com.wonday.farm.salary.crypto.SalaryAccountCrypto;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

// 급여 계좌 관리 검증용 가상 데이터·테스트 전용 키. 실제 계좌·.env 값이 아니다.
public final class SalaryAccountTestData {
	public static final JsonMapper JSON = JsonMapper.builder().build();
	public static final String KEY = key((byte) 0x66);
	public static final String OTHER_KEY = key((byte) 0x77);
	public static final String ACCOUNT = "1002-0030-0400";
	public static final String ACCOUNT_NORMALIZED = "100200300400";
	public static final List<String> SECRETS = List.of(ACCOUNT, ACCOUNT_NORMALIZED, "v1:", KEY, OTHER_KEY, "SQL", "select ",
		"jdbc", "Exception", "ciphertext", "Ciphertext");

	private SalaryAccountTestData() {}

	public static String key(byte fill) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, fill);
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static SalaryAccountCrypto crypto() {
		return new SalaryAccountCrypto(KEY);
	}

	public static Member member(long id, String role, long version) {
		var member = new Member("member-" + id, "hash", "회원 " + id, MemberRole.WORKER);
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "role", role);
		ReflectionTestUtils.setField(member, "salaryAccountVersion", version);
		return member;
	}

	public static Map<String, Object> body(long version) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("bankCode", "020");
		body.put("accountNumber", ACCOUNT);
		body.put("accountHolderName", "TEST WORKER");
		body.put("version", version);
		return body;
	}

	public static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

	// 운영 코드와 별개로 구현한 AES-GCM 암호화. 운영 maskedAccountNumber가 같은 형식(v1:, nonce 12 + 태그 16, AAD)을 읽는지 확인한다.
	public static String independentEncrypt(String key, long memberId, String bankCode, byte[] plaintext) throws Exception {
		byte[] nonce = new byte[12];
		new SecureRandom().nextBytes(nonce);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key), "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(("farm/member/salary-account/v1:" + memberId + ":" + bankCode).getBytes(StandardCharsets.US_ASCII));
		byte[] encrypted = cipher.doFinal(plaintext);
		byte[] packed = new byte[12 + encrypted.length];
		System.arraycopy(nonce, 0, packed, 0, 12);
		System.arraycopy(encrypted, 0, packed, 12, encrypted.length);
		return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
	}
}

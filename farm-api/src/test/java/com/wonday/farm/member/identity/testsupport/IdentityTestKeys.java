package com.wonday.farm.member.identity.testsupport;

import java.util.Arrays;
import java.util.Base64;

import com.wonday.farm.member.identity.IdentityCrypto;

// 테스트 전용 가상 키·번호. 실제 .env 값이나 실제 주민번호를 쓰지 않는다.
public final class IdentityTestKeys {
	public static final String ENCRYPTION_KEY = key((byte) 0x11);
	public static final String HMAC_KEY = key((byte) 0x22);
	public static final String OTHER_KEY = key((byte) 0x33);
	// 실제 번호처럼 보이지 않도록 같은 숫자 반복으로 만든 가상 번호다.
	public static final String NUMBER = "1111111111111";
	public static final String NUMBER_WITH_HYPHEN = "111111-1111111";
	public static final String OTHER_NUMBER = "2222222222222";

	private IdentityTestKeys() {}

	public static IdentityCrypto crypto() {
		return new IdentityCrypto(ENCRYPTION_KEY, HMAC_KEY);
	}

	public static String key(byte fill) {
		byte[] bytes = new byte[32];
		Arrays.fill(bytes, fill);
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static String key(int length, byte fill) {
		byte[] bytes = new byte[length];
		Arrays.fill(bytes, fill);
		return Base64.getEncoder().encodeToString(bytes);
	}
}

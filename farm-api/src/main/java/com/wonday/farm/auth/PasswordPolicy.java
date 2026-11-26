package com.wonday.farm.auth;

import java.nio.charset.StandardCharsets;

// 회원가입·데모 계정 등록·로그인 검증에 같은 BCrypt 입력 길이 제한을 적용한다.
public final class PasswordPolicy {
	public static final int BCRYPT_MAX_BYTES = 72;

	private PasswordPolicy() {
	}

	public static boolean exceedsBcryptLimit(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
	}
}

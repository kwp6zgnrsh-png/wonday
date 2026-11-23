package com.wonday.farm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

	@Test
	void allowsPasswordUpToBcryptLimit() {
		assertThat(PasswordPolicy.exceedsBcryptLimit("")).isFalse();
		assertThat(PasswordPolicy.exceedsBcryptLimit("a".repeat(PasswordPolicy.BCRYPT_MAX_BYTES))).isFalse();
	}

	@Test
	void rejectsPasswordOverBcryptLimit() {
		assertThat(PasswordPolicy.exceedsBcryptLimit("a".repeat(PasswordPolicy.BCRYPT_MAX_BYTES + 1))).isTrue();
	}

	@Test
	void measuresLengthInUtf8BytesNotCharacters() {
		// 한글 한 글자는 UTF-8로 3바이트이므로 글자 수는 적어도 바이트 기준으로 제한을 넘는다.
		String exactly72Bytes = "a".repeat(69) + "한";
		String over72Bytes = "a".repeat(70) + "한";
		assertThat(exactly72Bytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		assertThat(over72Bytes).hasSizeLessThan(72);

		assertThat(PasswordPolicy.exceedsBcryptLimit(exactly72Bytes)).isFalse();
		assertThat(PasswordPolicy.exceedsBcryptLimit(over72Bytes)).isTrue();
	}
}

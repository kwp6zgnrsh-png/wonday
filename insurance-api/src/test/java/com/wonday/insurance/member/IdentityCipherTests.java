package com.wonday.insurance.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class IdentityCipherTests {

	static final String TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
	private final IdentityCipher cipher = new IdentityCipher(TEST_KEY);

	@Test
	void bothIdentityTypesRoundTripWithRandomNonces() {
		for (IdentityType type : IdentityType.values()) {
			String first = cipher.encrypt(7, type, "0000000000000");
			String second = cipher.encrypt(7, type, "0000000000000");
			assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("0000000000000");
			assertThat(cipher.decrypt(7, type, first)).isEqualTo("0000000000000");
		}
	}

	@Test
	void copiedOrTamperedCiphertextCannotBeDecrypted() {
		String encrypted = cipher.encrypt(7, IdentityType.RESIDENT_REGISTRATION_NUMBER, "0000000000000");
		assertThatThrownBy(() -> cipher.decrypt(8, IdentityType.RESIDENT_REGISTRATION_NUMBER, encrypted))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cipher.decrypt(7, IdentityType.FOREIGN_REGISTRATION_NUMBER, encrypted))
			.isInstanceOf(IllegalStateException.class);
		byte[] bytes = Base64.getDecoder().decode(encrypted.substring(3));
		bytes[bytes.length - 1] ^= 1;
		assertThatThrownBy(() -> cipher.decrypt(7, IdentityType.RESIDENT_REGISTRATION_NUMBER,
			"v1:" + Base64.getEncoder().encodeToString(bytes))).isInstanceOf(IllegalStateException.class);
		IdentityCipher otherKey = new IdentityCipher(Base64.getEncoder().encodeToString(new byte[32]));
		assertThatThrownBy(() -> otherKey.decrypt(7, IdentityType.RESIDENT_REGISTRATION_NUMBER, encrypted))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void malformedCiphertextDoesNotLeakItsValueInExceptions() {
		for (String input : new String[]{null, "v2:secret", "v1:secret", "v1:", "plaintext-identity"}) {
			assertThatThrownBy(() -> cipher.decrypt(7, IdentityType.RESIDENT_REGISTRATION_NUMBER, input))
				.isInstanceOf(IllegalStateException.class).hasMessage("Identity decryption failed").hasNoCause();
		}
	}

	@Test
	void missingOrInvalidKeysFailWithoutIncludingTheSecret() {
		for (String key : new String[]{"", "do-not-log-this!", Base64.getEncoder().encodeToString(new byte[16])}) {
			assertThatThrownBy(() -> new IdentityCipher(key)).isInstanceOf(IllegalStateException.class)
				.hasMessage("INSURANCE_IDENTITY_ENCRYPTION_KEY must be Base64 for 32 random bytes").hasNoCause();
		}
	}
}

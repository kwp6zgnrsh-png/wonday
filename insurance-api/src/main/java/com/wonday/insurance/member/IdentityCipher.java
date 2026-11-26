package com.wonday.insurance.member;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class IdentityCipher {

	private static final String PREFIX = "v1:";
	private static final int NONCE_BYTES = 12;
	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public IdentityCipher(@Value("${insurance.identity.encryption-key:}") String encodedKey) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(encodedKey);
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException("INSURANCE_IDENTITY_ENCRYPTION_KEY must be Base64 for 32 random bytes");
		}
		if (decoded.length != 32) {
			throw new IllegalStateException("INSURANCE_IDENTITY_ENCRYPTION_KEY must be Base64 for 32 random bytes");
		}
		this.key = new SecretKeySpec(decoded, "AES");
	}

	public String encrypt(long memberId, IdentityType type, String identityNumber) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, memberId, type);
			byte[] encrypted = cipher.doFinal(identityNumber.getBytes(StandardCharsets.UTF_8));
			return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
				.put(nonce).put(encrypted).array());
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Identity encryption failed");
		}
	}

	public String decrypt(long memberId, IdentityType type, String ciphertext) {
		try {
			if (ciphertext == null || !ciphertext.startsWith(PREFIX)) throw new IllegalArgumentException();
			byte[] packed = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
			if (packed.length <= NONCE_BYTES + 16) throw new IllegalArgumentException();
			ByteBuffer buffer = ByteBuffer.wrap(packed);
			byte[] nonce = new byte[NONCE_BYTES];
			buffer.get(nonce);
			byte[] encrypted = new byte[buffer.remaining()];
			buffer.get(encrypted);
			return new String(cipher(Cipher.DECRYPT_MODE, nonce, memberId, type).doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException exception) {
			// 암호문과 원문, 키가 예외 메시지나 원인 체인에 섞이지 않게 한다.
			throw new IllegalStateException("Identity decryption failed");
		}
	}

	private Cipher cipher(int mode, byte[] nonce, long memberId, IdentityType type) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(128, nonce));
		// 다른 회원이나 식별번호 종류로 암호문을 옮기면 복호화에 실패하도록 문맥도 인증한다.
		cipher.updateAAD(("insurance:member:" + memberId + ":" + type.name() + ":v1").getBytes(StandardCharsets.UTF_8));
		return cipher;
	}
}

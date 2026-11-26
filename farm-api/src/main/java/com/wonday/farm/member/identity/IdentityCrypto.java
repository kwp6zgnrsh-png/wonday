package com.wonday.farm.member.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// JDK의 AES-GCM·HMAC-SHA256을 사용한다. 키는 데이터와 분리한 환경 설정에서만 받는다.
@Component
public class IdentityCrypto {
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final byte[] INDEX_DOMAIN = "farm/member/identity-hmac/v1:".getBytes(StandardCharsets.US_ASCII);
	private final SecureRandom random = new SecureRandom();
	private final SecretKeySpec encryptionKey;
	private final SecretKeySpec hmacKey;
	private final boolean configured;

	public IdentityCrypto(
			@Value("${farm.identity.encryption-key:}") String encryptionKeyBase64,
			@Value("${farm.identity.hmac-key:}") String hmacKeyBase64) {
		byte[] encryption = decodeKey(encryptionKeyBase64);
		byte[] index = decodeKey(hmacKeyBase64);
		configured = encryption != null && index != null && !MessageDigest.isEqual(encryption, index);
		encryptionKey = encryption == null ? null : new SecretKeySpec(encryption, "AES");
		hmacKey = index == null ? null : new SecretKeySpec(index, "HmacSHA256");
		if (encryption != null) Arrays.fill(encryption, (byte) 0);
		if (index != null) Arrays.fill(index, (byte) 0);
		// 미설정 상태에서도 기존 로그인·공고 기능은 기동한다. 암호화 기능 사용 시에는 안전하게 거절한다.
	}

	public void requireConfigured() {
		if (!configured) throw new IdentityCryptoUnavailableException();
	}

	public StoredIdentity protect(long memberId, NationalityType nationality, String rawNumber) {
		if (memberId <= 0 || nationality == null) throw new IllegalArgumentException("회원 식별정보를 확인해 주세요.");
		String normalized = IdentityNumber.normalize(rawNumber);
		requireConfigured();
		byte[] plaintext = normalized.getBytes(StandardCharsets.US_ASCII);
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(memberId, nationality.identityType()));
			byte[] encrypted = cipher.doFinal(plaintext);
			byte[] packed = new byte[nonce.length + encrypted.length];
			System.arraycopy(nonce, 0, packed, 0, nonce.length);
			System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
			String encoded = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
			return new StoredIdentity(memberId, nationality, nationality.identityType(), encoded, blindIndex(normalized));
		} catch (GeneralSecurityException exception) {
			throw new IdentityCryptoUnavailableException();
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	public String blindIndex(String rawNumber) {
		String normalized = IdentityNumber.normalize(rawNumber);
		requireConfigured();
		byte[] plaintext = normalized.getBytes(StandardCharsets.US_ASCII);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(hmacKey);
			mac.update(INDEX_DOMAIN);
			// 종류를 포함하지 않아 동일한 정규화 번호의 중복 가입을 두 종류에 걸쳐 차단한다.
			return HexFormat.of().formatHex(mac.doFinal(plaintext));
		} catch (GeneralSecurityException exception) {
			throw new IdentityCryptoUnavailableException();
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	// 명시적 이관의 무결성 확인용이다. 사용자 응답으로 복호화 번호를 반환하지 않는다.
	public String decryptForMigration(long memberId, IdentityType type, String encoded) {
		requireConfigured();
		if (memberId <= 0 || type == null || encoded == null || !encoded.startsWith("v1:") || encoded.length() > 256) {
			throw new IdentityCryptoUnavailableException();
		}
		byte[] plaintext = null;
		try {
			byte[] packed = Base64.getUrlDecoder().decode(encoded.substring(3));
			if (packed.length != NONCE_BYTES + 13 + TAG_BITS / 8) throw new IdentityCryptoUnavailableException();
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
				new GCMParameterSpec(TAG_BITS, Arrays.copyOf(packed, NONCE_BYTES)));
			cipher.updateAAD(aad(memberId, type));
			plaintext = cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
			return IdentityNumber.normalize(new String(plaintext, StandardCharsets.US_ASCII));
		} catch (GeneralSecurityException | IllegalArgumentException exception) {
			throw new IdentityCryptoUnavailableException();
		} finally {
			if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
		}
	}

	private static byte[] aad(long memberId, IdentityType type) {
		return ("farm/member/identity/v1:" + memberId + ":" + type.name()).getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] decodeKey(String encoded) {
		byte[] bytes = null;
		try {
			if (encoded == null || encoded.isBlank()) return null;
			bytes = Base64.getDecoder().decode(encoded);
			if (bytes.length == 32) return bytes;
		} catch (IllegalArgumentException ignored) {
			// 설정 원문을 예외나 로그로 보내지 않는다.
		}
		if (bytes != null) Arrays.fill(bytes, (byte) 0);
		return null;
	}
}
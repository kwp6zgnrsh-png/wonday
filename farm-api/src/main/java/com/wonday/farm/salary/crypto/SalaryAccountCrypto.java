package com.wonday.farm.salary.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 식별번호와 분리한 계좌 전용 키를 환경변수로 받는다. 계좌가 없는 가입과 기존 로그인은 키 없이 유지한다.
@Component
public class SalaryAccountCrypto {
	private final SecureRandom random = new SecureRandom();
	private final SecretKeySpec key;

	public SalaryAccountCrypto(@Value("${farm.salary-account.encryption-key:}") String encodedKey) {
		byte[] decoded = decodeKey(encodedKey);
		key = decoded == null ? null : new SecretKeySpec(decoded, "AES");
		if (decoded != null) Arrays.fill(decoded, (byte) 0);
	}

	public void requireConfigured() {
		if (key == null) throw new SalaryAccountCryptoUnavailableException();
	}

	public String encrypt(long memberId, String bankCode, String normalizedNumber) {
		if (memberId <= 0 || bankCode == null || !bankCode.matches("[0-9]{3}")
				|| normalizedNumber == null || !normalizedNumber.matches("[0-9]{1,30}")) {
			throw new IllegalArgumentException("급여 계좌 연결을 확인해 주세요.");
		}
		requireConfigured();
		byte[] plaintext = normalizedNumber.getBytes(StandardCharsets.US_ASCII);
		byte[] nonce = new byte[12];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
			// 암호문을 회원 ID·은행 코드에 결합해 다른 회원·은행의 값으로 재사용하지 못하게 한다.
			cipher.updateAAD(("farm/member/salary-account/v1:" + memberId + ":" + bankCode).getBytes(StandardCharsets.US_ASCII));
			byte[] encrypted = cipher.doFinal(plaintext);
			byte[] packed = new byte[nonce.length + encrypted.length];
			System.arraycopy(nonce, 0, packed, 0, nonce.length);
			System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
			return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
		} catch (GeneralSecurityException exception) {
			throw new SalaryAccountCryptoUnavailableException();
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	private static byte[] decodeKey(String encoded) {
		byte[] decoded = null;
		try {
			if (encoded == null || encoded.isBlank()) return null;
			decoded = Base64.getDecoder().decode(encoded);
			if (decoded.length == 32) return decoded;
		} catch (IllegalArgumentException ignored) {
			// 설정 원문을 예외에 포함하지 않는다.
		}
		if (decoded != null) Arrays.fill(decoded, (byte) 0);
		return null;
	}
}

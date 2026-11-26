package com.wonday.farm.member.identity;

import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.ENCRYPTION_KEY;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.HMAC_KEY;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.NUMBER;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.NUMBER_WITH_HYPHEN;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.OTHER_KEY;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.OTHER_NUMBER;
import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// IdentityCrypto의 공개 동작(왕복·nonce·HMAC·변조/키/AAD 거절·설정 실패 시점·비노출)을 테스트용 가상 키로 확인한다.
// 몇 개의 테스트로 AES-GCM·HMAC의 암호학적 안전성이나 JDK 내부 구현을 입증하지 않는다.
@DisplayName("식별정보 암호화·HMAC (IdentityCrypto)")
class IdentityCryptoTest {
	private final IdentityCrypto crypto = IdentityTestKeys.crypto();

	@Nested
	@DisplayName("암호화·복호화")
	class RoundTrip {
		@Test
		@DisplayName("암호화 후 같은 회원 ID·종류로 복호화하면 정규화된 원문이 나온다 (내국인·외국인)")
		void roundTrip() {
			StoredIdentity domestic = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER_WITH_HYPHEN);
			StoredIdentity foreign = crypto.protect(8L, NationalityType.FOREIGN, " 222222 2222222 ");

			assertThat(crypto.decryptForMigration(7L, IdentityType.RESIDENT_REGISTRATION_NUMBER, domestic.ciphertext())).isEqualTo(NUMBER);
			assertThat(crypto.decryptForMigration(8L, IdentityType.FOREIGN_REGISTRATION_NUMBER, foreign.ciphertext())).isEqualTo(OTHER_NUMBER);
			assertThat(domestic.memberId()).isEqualTo(7L);
			assertThat(domestic.nationalityType()).isEqualTo(NationalityType.DOMESTIC);
			assertThat(domestic.identityType()).isEqualTo(IdentityType.RESIDENT_REGISTRATION_NUMBER);
			assertThat(foreign.identityType()).isEqualTo(IdentityType.FOREIGN_REGISTRATION_NUMBER);
		}

		@Test
		@DisplayName("암호문 형식: v1: + URL-safe Base64(패딩 없음), nonce 12 + 평문 13 + 태그 16 = 41바이트, 원문 숫자가 보이지 않음")
		void ciphertextFormat() {
			String ciphertext = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER).ciphertext();

			assertThat(ciphertext).startsWith("v1:").doesNotContain("=", "+", "/", NUMBER);
			assertThat(Base64.getUrlDecoder().decode(ciphertext.substring(3))).hasSize(41);
		}

		@Test
		@DisplayName("같은 원문을 반복 암호화하면 nonce가 달라 암호문은 매번 다르고 HMAC은 같다")
		void freshNonce() {
			Set<String> ciphertexts = new HashSet<>();
			Set<String> nonces = new HashSet<>();
			Set<String> hmacs = new HashSet<>();
			for (int i = 0; i < 50; i++) {
				StoredIdentity stored = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);
				ciphertexts.add(stored.ciphertext());
				nonces.add(Base64.getEncoder().encodeToString(
					java.util.Arrays.copyOf(Base64.getUrlDecoder().decode(stored.ciphertext().substring(3)), 12)));
				hmacs.add(stored.hmac());
			}
			assertThat(ciphertexts).hasSize(50);
			assertThat(nonces).hasSize(50);
			assertThat(hmacs).hasSize(1);
		}
	}

	@Nested
	@DisplayName("HMAC")
	class Hmac {
		@Test
		@DisplayName("동일 정규화 번호(하이픈·공백 차이)는 같은 HMAC, 다른 번호는 다른 HMAC. 64자리 소문자 16진수")
		void sameAndDifferent() {
			String hmac = crypto.blindIndex(NUMBER);

			assertThat(crypto.blindIndex(NUMBER_WITH_HYPHEN)).isEqualTo(hmac);
			assertThat(crypto.blindIndex(" 111111 - 1111111 ")).isEqualTo(hmac);
			assertThat(crypto.blindIndex(OTHER_NUMBER)).isNotEqualTo(hmac);
			assertThat(crypto.blindIndex("1111111111112")).isNotEqualTo(hmac);
			assertThat(hmac).matches("[0-9a-f]{64}").doesNotContain(NUMBER);
		}

		@Test
		@DisplayName("현재 계약: 같은 번호는 국적·번호 종류·회원 ID를 달리해도 HMAC이 같아 중복을 우회하지 못한다")
		void hmacIgnoresTypeAndMember() {
			StoredIdentity domestic = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);
			StoredIdentity foreign = crypto.protect(8L, NationalityType.FOREIGN, NUMBER_WITH_HYPHEN);

			assertThat(foreign.hmac()).isEqualTo(domestic.hmac()).isEqualTo(crypto.blindIndex(NUMBER));
		}

		@Test
		@DisplayName("HMAC 키가 다르면 HMAC이 다르고, 암호화 키만 다르면 HMAC은 같다 (두 키가 분리돼 쓰임)")
		void keysAreSeparated() {
			assertThat(new IdentityCrypto(ENCRYPTION_KEY, OTHER_KEY).blindIndex(NUMBER)).isNotEqualTo(crypto.blindIndex(NUMBER));
			assertThat(new IdentityCrypto(OTHER_KEY, HMAC_KEY).blindIndex(NUMBER)).isEqualTo(crypto.blindIndex(NUMBER));
		}
	}

	@Nested
	@DisplayName("복호화 거절 — 모두 IdentityCryptoUnavailableException, 원인·원문 비노출")
	class Rejection {
		private final StoredIdentity stored = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);

		@Test
		@DisplayName("암호문의 nonce·본문·태그 위치를 각각 1비트 바꾸면 거절")
		void tampered() {
			byte[] packed = Base64.getUrlDecoder().decode(stored.ciphertext().substring(3));
			for (int index : new int[] {0, 11, 12, 24, 25, 40}) {
				byte[] copy = packed.clone();
				copy[index] ^= 0x01;
				String tampered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
				assertRejected(() -> crypto.decryptForMigration(7L, IdentityType.RESIDENT_REGISTRATION_NUMBER, tampered));
			}
		}

		@Test
		@DisplayName("다른 암호화 키·다른 회원 ID·다른 식별번호 종류로는 복호화하지 못한다 (AAD 결합)")
		void wrongKeyMemberOrType() {
			assertRejected(() -> new IdentityCrypto(OTHER_KEY, HMAC_KEY)
				.decryptForMigration(7L, IdentityType.RESIDENT_REGISTRATION_NUMBER, stored.ciphertext()));
			assertRejected(() -> crypto.decryptForMigration(8L, IdentityType.RESIDENT_REGISTRATION_NUMBER, stored.ciphertext()));
			assertRejected(() -> crypto.decryptForMigration(7L, IdentityType.FOREIGN_REGISTRATION_NUMBER, stored.ciphertext()));
		}

		@Test
		@DisplayName("잘못된 형식·버전·길이·null·ID·종류는 거절")
		void malformed() {
			String body = stored.ciphertext().substring(3);
			byte[] packed = Base64.getUrlDecoder().decode(body);
			String shorter = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(packed, 40));
			String longer = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(packed, 42));
			for (String encoded : new String[] {null, "", "v1:", "v2:" + body, "V1:" + body, body, "v1:" + body + "!",
					"v1:@@@@", shorter, longer, "v1:" + "A".repeat(254)}) {
				assertRejected(() -> crypto.decryptForMigration(7L, IdentityType.RESIDENT_REGISTRATION_NUMBER, encoded));
			}
			assertRejected(() -> crypto.decryptForMigration(0L, IdentityType.RESIDENT_REGISTRATION_NUMBER, stored.ciphertext()));
			assertRejected(() -> crypto.decryptForMigration(7L, null, stored.ciphertext()));
		}
	}

	@Nested
	@DisplayName("키 설정 — 생성은 성공, 실제 암호화 작업에서 안전하게 실패")
	class Configuration {
		@ParameterizedTest(name = "{0}")
		@MethodSource("com.wonday.farm.member.identity.IdentityCryptoTest#badKeys")
		@DisplayName("키 누락·Base64 오류·길이 오류·두 키 동일")
		void unavailable(String label, String encryption, String hmac) {
			IdentityCrypto broken = assertConstructs(encryption, hmac);

			assertRejected(broken::requireConfigured);
			assertRejected(() -> broken.protect(7L, NationalityType.DOMESTIC, NUMBER));
			assertRejected(() -> broken.blindIndex(NUMBER));
			assertRejected(() -> broken.decryptForMigration(7L, IdentityType.RESIDENT_REGISTRATION_NUMBER,
				crypto.protect(7L, NationalityType.DOMESTIC, NUMBER).ciphertext()));
		}

		@Test
		@DisplayName("정상 키는 requireConfigured를 통과한다")
		void configured() {
			assertThatCode(crypto::requireConfigured).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("입력 오류가 설정 오류보다 먼저 판정된다: 미설정이어도 잘못된 번호·회원 ID는 IllegalArgumentException")
		void inputCheckedBeforeConfiguration() {
			IdentityCrypto broken = new IdentityCrypto("", "");

			assertThatThrownBy(() -> broken.protect(7L, NationalityType.DOMESTIC, "abc")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> broken.blindIndex("abc")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> crypto.protect(0L, NationalityType.DOMESTIC, NUMBER)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> crypto.protect(7L, null, NUMBER)).isInstanceOf(IllegalArgumentException.class);
		}

		private IdentityCrypto assertConstructs(String encryption, String hmac) {
			IdentityCrypto[] holder = new IdentityCrypto[1];
			assertThatCode(() -> holder[0] = new IdentityCrypto(encryption, hmac)).doesNotThrowAnyException();
			return holder[0];
		}
	}

	@Nested
	@DisplayName("비노출")
	class Redaction {
		@Test
		@DisplayName("StoredIdentity·IdentityCrypto의 toString과 예외 메시지에 번호·키·암호문·HMAC이 없고 원인 체인도 없다")
		void noLeak() {
			StoredIdentity stored = crypto.protect(7L, NationalityType.DOMESTIC, NUMBER);
			String hmacKeyHex = java.util.HexFormat.of().formatHex(Base64.getDecoder().decode(HMAC_KEY));

			assertThat(stored.toString()).isEqualTo("StoredIdentity[redacted]");
			assertThat(crypto.toString()).doesNotContain(ENCRYPTION_KEY, HMAC_KEY, hmacKeyHex);
			Throwable error = catchUnavailable(() -> crypto.decryptForMigration(8L, IdentityType.RESIDENT_REGISTRATION_NUMBER, stored.ciphertext()));
			assertThat(error.getMessage()).doesNotContain(NUMBER, stored.ciphertext(), stored.hmac(), ENCRYPTION_KEY);
			assertThat(error.getCause()).isNull();
			assertThat(error.getSuppressed()).isEmpty();
			Throwable config = catchUnavailable(() -> new IdentityCrypto("not-base64!!" + ENCRYPTION_KEY, HMAC_KEY).blindIndex(NUMBER));
			assertThat(config.getMessage()).doesNotContain("not-base64", ENCRYPTION_KEY, HMAC_KEY);
			assertThat(config.getCause()).isNull();
		}
	}

	static Stream<Arguments> badKeys() {
		return Stream.of(
			Arguments.of("둘 다 빈 값", "", ""),
			Arguments.of("둘 다 null", null, null),
			Arguments.of("공백", "   ", HMAC_KEY),
			Arguments.of("암호화 키 누락", "", HMAC_KEY),
			Arguments.of("HMAC 키 누락", ENCRYPTION_KEY, ""),
			Arguments.of("Base64 오류", "@@@@", HMAC_KEY),
			Arguments.of("URL-safe 문자(-_)는 표준 Base64 아님", ENCRYPTION_KEY.replace('E', '-'), HMAC_KEY),
			Arguments.of("16바이트", key(16, (byte) 0x11), HMAC_KEY),
			Arguments.of("31바이트", key(31, (byte) 0x11), HMAC_KEY),
			Arguments.of("33바이트", ENCRYPTION_KEY, key(33, (byte) 0x22)),
			Arguments.of("64바이트", key(64, (byte) 0x11), HMAC_KEY),
			Arguments.of("두 키 동일", ENCRYPTION_KEY, ENCRYPTION_KEY),
			Arguments.of("같은 바이트의 다른 표기(앞뒤 공백 아님, 동일 값)", key((byte) 0x44), key((byte) 0x44)));
	}

	private static void assertRejected(ThrowingCallable call) {
		assertThatThrownBy(call).isExactlyInstanceOf(IdentityCryptoUnavailableException.class)
			.hasMessage("식별정보 암호화 설정 또는 저장 상태를 확인할 수 없습니다.")
			.hasNoCause();
	}

	private static Throwable catchUnavailable(ThrowingCallable call) {
		Throwable error = org.assertj.core.api.Assertions.catchThrowable(call);
		assertThat(error).isExactlyInstanceOf(IdentityCryptoUnavailableException.class);
		return error;
	}
}

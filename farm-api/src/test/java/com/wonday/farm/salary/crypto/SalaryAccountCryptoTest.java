package com.wonday.farm.salary.crypto;

import static com.wonday.farm.member.signup.testsupport.SignupTestData.ACCOUNT_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.AEADBadTagException;

import com.wonday.farm.member.signup.testsupport.SignupTestData;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

// 계좌 암호화 공개 동작과 계좌 엔티티. 운영 코드에는 복호화 API가 없으므로 테스트 안에서만 JDK Cipher로 복호화해
// 원문·AAD(회원 ID·은행 코드) 결합을 확인한다. 몇 개의 테스트로 암호학적 안전성을 입증하지 않는다.
@DisplayName("급여 계좌 암호화·엔티티")
class SalaryAccountCryptoTest {
	private final SalaryAccountCrypto crypto = new SalaryAccountCrypto(ACCOUNT_KEY);

	@Nested
	@DisplayName("암호화")
	class Encrypt {
		@Test
		@DisplayName("v1: + URL-safe Base64, 평문 계좌번호가 보이지 않고 테스트 복호화 시 정규화 원문이 나온다")
		void format() throws Exception {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");

			assertThat(ciphertext).startsWith("v1:").doesNotContain("100200300400", "=", "+", "/");
			assertThat(decrypt(ciphertext, 101L, "020")).isEqualTo("100200300400");
		}

		@Test
		@DisplayName("앞자리 0·1자리·30자리 번호도 그대로 왕복")
		void lengths() throws Exception {
			for (String number : new String[] {"0", "0012345", "123456789012345678901234567890"}) {
				assertThat(decrypt(crypto.encrypt(5L, "004", number), 5L, "004")).isEqualTo(number);
			}
		}

		@Test
		@DisplayName("같은 번호를 반복 암호화하면 nonce가 달라 암호문이 매번 다르다")
		void freshNonce() {
			Set<String> ciphertexts = new HashSet<>();
			Set<String> nonces = new HashSet<>();
			for (int i = 0; i < 30; i++) {
				String ciphertext = crypto.encrypt(101L, "020", "100200300400");
				ciphertexts.add(ciphertext);
				nonces.add(Arrays.toString(Arrays.copyOf(Base64.getUrlDecoder().decode(ciphertext.substring(3)), 12)));
			}
			assertThat(ciphertexts).hasSize(30);
			assertThat(nonces).hasSize(30);
		}

		@Test
		@DisplayName("다른 회원 ID·다른 은행 코드·다른 키로는 복호화되지 않는다 (AAD·키 결합)")
		void boundToMemberAndBank() {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");

			assertThatThrownBy(() -> decrypt(ciphertext, 102L, "020")).isInstanceOf(AEADBadTagException.class);
			assertThatThrownBy(() -> decrypt(ciphertext, 101L, "004")).isInstanceOf(AEADBadTagException.class);
			assertThatThrownBy(() -> decrypt(ciphertext, 101L, "020", SignupTestData.key((byte) 0x66)))
				.isInstanceOf(AEADBadTagException.class);
		}

		@Test
		@DisplayName("정규화되지 않은 입력(하이픈·31자리·문자)·잘못된 회원 ID·은행 코드는 IllegalArgumentException, 원문 비노출")
		void invalidInput() {
			for (String number : new String[] {"1002-0030", "1".repeat(31), "", "12a", null}) {
				assertThatThrownBy(() -> crypto.encrypt(1L, "020", number)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining("1002");
			}
			assertThatThrownBy(() -> crypto.encrypt(0L, "020", "1")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> crypto.encrypt(1L, "20", "1")).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> crypto.encrypt(1L, null, "1")).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("키 설정 — 생성은 성공, 사용 시 안전하게 거절")
	class Keys {
		@ParameterizedTest(name = "\"{0}\"")
		@NullSource
		@ValueSource(strings = {"", "   ", "@@@@", "AAAA", "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV",
			"not base64 with spaces"})
		@DisplayName("누락·공백·Base64 오류·32바이트가 아닌 키")
		void unavailable(String key) {
			SalaryAccountCrypto[] holder = new SalaryAccountCrypto[1];
			assertThatCode(() -> holder[0] = new SalaryAccountCrypto(key)).doesNotThrowAnyException();

			assertThatThrownBy(holder[0]::requireConfigured).isExactlyInstanceOf(SalaryAccountCryptoUnavailableException.class)
				.hasNoCause().hasMessage("급여 계좌 암호화 처리를 사용할 수 없습니다.");
			assertThatThrownBy(() -> holder[0].encrypt(1L, "020", "1")).isExactlyInstanceOf(SalaryAccountCryptoUnavailableException.class);
		}

		@Test
		@DisplayName("16·31·33바이트 키도 거절, 정상 32바이트 키는 통과")
		void lengths() {
			for (int length : new int[] {16, 31, 33}) {
				byte[] bytes = new byte[length];
				Arrays.fill(bytes, (byte) 0x55);
				assertThatThrownBy(new SalaryAccountCrypto(Base64.getEncoder().encodeToString(bytes))::requireConfigured)
					.isInstanceOf(SalaryAccountCryptoUnavailableException.class);
			}
			assertThatCode(crypto::requireConfigured).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("toString에 키가 노출되지 않는다")
		void noKeyInToString() {
			assertThat(crypto.toString()).doesNotContain(ACCOUNT_KEY);
		}
	}

	@Nested
	@DisplayName("MemberSalaryAccount 엔티티")
	class Entity {
		private final LocalDateTime now = LocalDateTime.parse("2026-11-26T01:02:03.123456");

		@Test
		@DisplayName("회원 ID PK·은행 코드·암호문·예금주·생성/수정 시각을 담고 toString은 가린다")
		void stores() throws Exception {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");
			var account = new MemberSalaryAccount(101L, "020", ciphertext, "TEST WORKER", now);

			assertThat(account.getMemberId()).isEqualTo(101L);
			assertThat(account.bankCode()).isEqualTo("020");
			assertThat(account.accountHolderName()).isEqualTo("TEST WORKER");
			assertThat(account.createdAt()).isEqualTo(now);
			assertThat(account.updatedAt()).isEqualTo(now);
			assertThat(account.toString()).isEqualTo("MemberSalaryAccount[redacted]").doesNotContain(ciphertext);
			Field id = MemberSalaryAccount.class.getDeclaredField("memberId");
			assertThat(id.getAnnotation(Id.class)).isNotNull();
			assertThat(id.getAnnotation(Column.class).name()).isEqualTo("member_id");
		}

		@Test
		@DisplayName("정적 확인: 계좌번호 원문 필드는 매핑하지 않고 암호문 컬럼만 있다. 암호문 getter도 없다")
		void noPlainNumber() {
			assertThat(Arrays.stream(MemberSalaryAccount.class.getDeclaredFields()).map(Field::getName))
				.containsExactlyInAnyOrder("memberId", "bankCode", "accountNumberCiphertext", "accountHolderName", "createdAt", "updatedAt");
			assertThat(Arrays.stream(MemberSalaryAccount.class.getDeclaredMethods()).map(m -> m.getName()))
				.noneMatch(name -> name.toLowerCase().contains("cipher") || name.toLowerCase().contains("number"));
		}

		@Test
		@DisplayName("회원 ID 0·은행 코드 형식·암호문 접두사·길이·예금주 공백·101자·시각 null은 거절")
		void rejects() {
			String valid = "v1:abc";
			assertInvalid(0L, "020", valid, "예금주", now);
			assertInvalid(1L, "20", valid, "예금주", now);
			assertInvalid(1L, null, valid, "예금주", now);
			assertInvalid(1L, "020", null, "예금주", now);
			assertInvalid(1L, "020", "v1:", "예금주", now);
			assertInvalid(1L, "020", "v2:abc", "예금주", now);
			assertInvalid(1L, "020", "100200300400", "예금주", now);
			assertInvalid(1L, "020", "v1:" + "a".repeat(254), "예금주", now);
			assertInvalid(1L, "020", valid, " ", now);
			assertInvalid(1L, "020", valid, null, now);
			assertInvalid(1L, "020", valid, "가".repeat(101), now);
			assertInvalid(1L, "020", valid, "예금주", null);
			assertThat(new MemberSalaryAccount(1L, "020", "v1:" + "a".repeat(253), "가".repeat(100), now).bankCode()).isEqualTo("020");
		}

		private void assertInvalid(long id, String bank, String ciphertext, String holder, LocalDateTime time) {
			assertThatThrownBy(() -> new MemberSalaryAccount(id, bank, ciphertext, holder, time)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	static String decrypt(String ciphertext, long memberId, String bankCode) throws Exception {
		return decrypt(ciphertext, memberId, bankCode, ACCOUNT_KEY);
	}

	static String decrypt(String ciphertext, long memberId, String bankCode, String key) throws Exception {
		return SignupTestData.decryptAccount(ciphertext, memberId, bankCode, key);
	}
}

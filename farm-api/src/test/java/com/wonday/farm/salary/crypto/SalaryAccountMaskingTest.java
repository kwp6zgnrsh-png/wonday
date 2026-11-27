package com.wonday.farm.salary.crypto;

import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.KEY;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.OTHER_KEY;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.independentEncrypt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;

import com.wonday.farm.member.MemberRole;
import com.wonday.farm.member.MemberRepository;
import com.wonday.farm.salary.entity.MemberSalaryAccount;
import com.wonday.farm.salary.service.SalaryAccountService;
import com.wonday.farm.salary.testsupport.SalaryAccountTestData;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 테스트 전용 가상 키(0x66 반복 32바이트)만 사용한다. 실제 .env·운영 키는 쓰지 않는다.
@DisplayName("급여 계좌 마스킹 복호화·엔티티 교체·회원 버전")
class SalaryAccountMaskingTest {
	private final SalaryAccountCrypto crypto = SalaryAccountTestData.crypto();

	@Nested
	@DisplayName("마스킹 (암호화 → 복호화 → 마스킹)")
	class Masking {
		@ParameterizedTest(name = "{0} → {1}")
		@CsvSource({
			"1, *",
			"0, *",
			"1234, ****",
			"0000, ****",
			"12345, *2345",
			"01234, *1234",
			"100200300400, ********0400",
			"000000000001, ********0001",
			"123456789012345678901234567890, **************************7890"
		})
		@DisplayName("1·4자리 전체 마스킹, 5자리 이상 끝 4자리만 표시, 앞자리 0·뒤 4자리 보존, 30자리 경계")
		void boundaries(String number, String expected) {
			String ciphertext = crypto.encrypt(101L, "020", number);
			String masked = crypto.maskedAccountNumber(101L, "020", ciphertext);
			assertThat(masked).isEqualTo(expected).hasSize(number.length());
			if (number.length() > 4) assertThat(masked).doesNotContain(number.substring(0, number.length() - 4));
		}

		@Test
		@DisplayName("가입용 암호문 형식 호환: 운영 코드와 별개로 만든 v1 AES-GCM 암호문(같은 AAD)을 마스킹한다")
		void compatibleWithSignupFormat() throws Exception {
			String signupStyle = independentEncrypt(KEY, 101L, "020", "100200300400".getBytes(StandardCharsets.US_ASCII));
			assertThat(crypto.maskedAccountNumber(101L, "020", signupStyle)).isEqualTo("********0400");
		}

		@Test
		@DisplayName("다른 회원 ID·다른 은행 코드·다른 키로는 마스킹하지 않고 503용 예외")
		void boundToMemberBankKey() {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");
			assertUnavailable(() -> crypto.maskedAccountNumber(102L, "020", ciphertext));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "004", ciphertext));
			assertUnavailable(() -> new SalaryAccountCrypto(OTHER_KEY).maskedAccountNumber(101L, "020", ciphertext));
		}

		@Test
		@DisplayName("암호문 1바이트 변조·잘린 암호문·잘못된 접두사·Base64 오류·길이 초과는 거절")
		void tampered() {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");
			byte[] packed = Base64.getUrlDecoder().decode(ciphertext.substring(3));
			packed[packed.length - 1] ^= 1;
			String flipped = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", flipped));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", ciphertext.substring(0, ciphertext.length() - 4)));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", "v2:" + ciphertext.substring(3)));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", "v1:%%%%"));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", "v1:"));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", "v1:" + "A".repeat(254)));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", null));
			assertUnavailable(() -> crypto.maskedAccountNumber(0L, "020", ciphertext));
			assertUnavailable(() -> crypto.maskedAccountNumber(101L, "20", ciphertext));
		}

		@Test
		@DisplayName("복호화 결과가 숫자가 아니거나 31자리·0자리면 마스킹하지 않는다 (같은 키·AAD로 만든 비정상 평문)")
		void invalidPlaintext() throws Exception {
			for (byte[] plaintext : new byte[][] {"1002-0030".getBytes(StandardCharsets.US_ASCII),
					"1".repeat(31).getBytes(StandardCharsets.US_ASCII), new byte[0], "１２３４５".getBytes(StandardCharsets.UTF_8)}) {
				String ciphertext = independentEncrypt(KEY, 101L, "020", plaintext);
				assertUnavailable(() -> crypto.maskedAccountNumber(101L, "020", ciphertext));
			}
		}

		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"", " ", "not-base64!", "AAAA"})
		@DisplayName("키 미설정·잘못된 키는 생성은 되지만 마스킹 시 거절")
		void unconfigured(String key) {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");
			assertUnavailable(() -> new SalaryAccountCrypto(key).maskedAccountNumber(101L, "020", ciphertext));
		}

		@Test
		@DisplayName("예외 메시지·원인에 번호·암호문·키가 없다")
		void exceptionRedacted() {
			String ciphertext = crypto.encrypt(101L, "020", "100200300400");
			Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> crypto.maskedAccountNumber(102L, "020", ciphertext));
			assertThat(thrown).isInstanceOf(SalaryAccountCryptoUnavailableException.class).hasNoCause();
			assertThat(thrown.getMessage()).doesNotContain("100200300400", "0400", ciphertext, KEY);
		}

		private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
			assertThatThrownBy(call).isInstanceOf(SalaryAccountCryptoUnavailableException.class);
		}
	}

	@Nested
	@DisplayName("엔티티 교체·회원 버전")
	class EntityAndVersion {
		private final LocalDateTime created = LocalDateTime.parse("2026-11-26T01:02:03.123456");
		private final LocalDateTime updated = LocalDateTime.parse("2026-11-27T04:05:06.654321");

		@Test
		@DisplayName("replace는 은행·암호문·예금주·수정 시각을 바꾸고 회원 ID·생성 시각은 보존한다")
		void replace() {
			String first = crypto.encrypt(101L, "020", "100200300400");
			var account = new MemberSalaryAccount(101L, "020", first, "TEST WORKER", created);
			String second = crypto.encrypt(101L, "004", "00123456789012");

			account.replace("004", second, "NEW HOLDER", updated);

			assertThat(account.getMemberId()).isEqualTo(101L);
			assertThat(account.bankCode()).isEqualTo("004");
			assertThat(account.ciphertextForDecryption()).isEqualTo(second);
			assertThat(account.accountHolderName()).isEqualTo("NEW HOLDER");
			assertThat(account.createdAt()).isEqualTo(created);
			assertThat(account.updatedAt()).isEqualTo(updated);
			assertThat(crypto.maskedAccountNumber(101L, "004", account.ciphertextForDecryption())).isEqualTo("**********9012");
			assertThat(account.toString()).doesNotContain(second, "9012");
		}

		@Test
		@DisplayName("잘못된 교체 값은 거절하고 기존 값을 하나도 바꾸지 않는다")
		void invalidReplaceKeepsState() {
			String first = crypto.encrypt(101L, "020", "100200300400");
			var account = new MemberSalaryAccount(101L, "020", first, "TEST WORKER", created);
			String valid = crypto.encrypt(101L, "004", "1234");
			for (Runnable bad : new Runnable[] {
					() -> account.replace("04", valid, "A", updated),
					() -> account.replace("004", "plain", "A", updated),
					() -> account.replace("004", valid, "  ", updated),
					() -> account.replace("004", valid, "가".repeat(101), updated),
					() -> account.replace("004", valid, "A", null)}) {
				assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
			}
			assertThat(account.bankCode()).isEqualTo("020");
			assertThat(account.ciphertextForDecryption()).isEqualTo(first);
			assertThat(account.accountHolderName()).isEqualTo("TEST WORKER");
			assertThat(account.updatedAt()).isEqualTo(created);
		}

		@Test
		@DisplayName("회원 계좌 버전은 0에서 시작해 1씩 증가하고, Long 최대값에서는 넘치지 않고 예외")
		void memberVersion() {
			var member = SalaryAccountTestData.member(101L, "WORKER", 0);
			assertThat(new com.wonday.farm.member.Member("x", "h", "n", MemberRole.WORKER).salaryAccountVersion()).isZero();
			member.advanceSalaryAccountVersion();
			member.advanceSalaryAccountVersion();
			assertThat(member.salaryAccountVersion()).isEqualTo(2);

			var max = SalaryAccountTestData.member(101L, "WORKER", Long.MAX_VALUE);
			assertThatThrownBy(max::advanceSalaryAccountVersion).isInstanceOf(ArithmeticException.class);
			assertThat(max.salaryAccountVersion()).isEqualTo(Long.MAX_VALUE);
		}

		@Test
		@DisplayName("선언·정적 확인 (실제 MySQL 잠금·스냅샷·롤백·V10 적용은 미검증): 버전 컬럼, 회원 잠금, 트랜잭션, V10 SQL")
		void declarations() throws Exception {
			var column = com.wonday.farm.member.Member.class.getDeclaredField("salaryAccountVersion").getAnnotation(Column.class);
			assertThat(column.name()).isEqualTo("salary_account_version");
			assertThat(column.nullable()).isFalse();

			var lock = MemberRepository.class.getMethod("findByIdForSalaryAccountUpdate", long.class).getAnnotation(Lock.class);
			assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);

			var find = SalaryAccountService.class.getMethod("findMine", long.class).getAnnotation(Transactional.class);
			assertThat(find.readOnly()).isTrue();
			assertThat(find.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
			assertThat(SalaryAccountService.class.getMethod("replace", long.class, com.wonday.farm.salary.dto.SalaryAccountRequest.class)
				.getAnnotation(Transactional.class).readOnly()).isFalse();
			assertThat(SalaryAccountService.class.getMethod("remove", long.class, long.class)
				.getAnnotation(Transactional.class).readOnly()).isFalse();

			String v10 = Files.readString(Path.of("src/main/resources/db/migration/V10__add_salary_account_version.sql")).replaceAll("\\s+", " ");
			assertThat(v10).contains("ALTER TABLE member", "ADD COLUMN salary_account_version BIGINT NOT NULL DEFAULT 0",
				"CHECK (salary_account_version >= 0)");
			assertThat(v10.toUpperCase()).doesNotContain("DROP ", "UPDATE ", "DELETE ");
		}
	}
}

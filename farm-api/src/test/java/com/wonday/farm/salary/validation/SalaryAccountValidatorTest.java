package com.wonday.farm.salary.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// 계좌 입력 형식 규칙(은행 코드 숫자 3자리, 숫자·ASCII 하이픈, 하이픈 제거 후 1~30자리, 예금주 앞뒤 공백 제거).
// 실제 은행 코드 목록·계좌 실재·예금주 일치·은행별 길이는 검사하지 않는 계약이다.
@DisplayName("급여 계좌 입력 정규화 (SalaryAccountValidator)")
class SalaryAccountValidatorTest {

	@ParameterizedTest(name = "{0} / {1}")
	@CsvSource({"020, 1002-0030-0400, 100200300400", "004, 123456-78-901234, 12345678901234", "088, 0, 0",
		"999, 000-000, 000000", "001, 1-2-3, 123", "020, --1--, 1", "000, 123456789012345678901234567890, 123456789012345678901234567890"})
	@DisplayName("형식에 맞는 모든 숫자 3자리 은행 코드와 1~30자리 숫자(하이픈 위치 무관, 앞자리 0 유지)를 받는다")
	void accepts(String bank, String number, String normalized) {
		var account = SalaryAccountValidator.normalize(bank, number, "예금주");

		assertThat(account.bankCode()).isEqualTo(bank);
		assertThat(account.accountNumber()).isEqualTo(normalized);
	}

	@Test
	@DisplayName("은행별 대표 길이와 달라도 거절하지 않는다 (우리 020에 5자리·20자리)")
	void noBankSpecificLength() {
		assertThat(SalaryAccountValidator.normalize("020", "12345", "예금주").accountNumber()).hasSize(5);
		assertThat(SalaryAccountValidator.normalize("020", "1".repeat(20), "예금주").accountNumber()).hasSize(20);
	}

	@Test
	@DisplayName("하이픈 포함 원문 100자까지 받고 101자는 거절 (숫자 30자리 + 하이픈 70개 = 100자)")
	void rawLength() {
		String hundred = "1".repeat(30) + "-".repeat(70);
		assertThat(SalaryAccountValidator.normalize("020", hundred, "예금주").accountNumber()).hasSize(30);
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", hundred + "-", "예금주")).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest(name = "계좌 \"{0}\"")
	@ValueSource(strings = {"", "-", "---", "1111111111111111111111111111111", "1111111111111111111111111111111-", "1002 0030 0400", " 1002", "1002_0030", "1002.0030",
		"1002–0030", "１００２", "1002a", "+1002", "1002\t0030"})
	@DisplayName("숫자 31자리·하이픈만·공백·기타 문자·전각 숫자·en dash는 거절하고 자르거나 바꾸지 않는다")
	void rejectsNumber(String number) {
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", number, "예금주"))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(number.isEmpty() ? "\u0000" : number);
	}

	@ParameterizedTest(name = "은행 \"{0}\"")
	@ValueSource(strings = {"", "20", "0200", "02a", " 020", "０２０", "-20"})
	@DisplayName("은행 코드는 ASCII 숫자 정확히 3자리 문자열")
	void rejectsBank(String bank) {
		assertThatThrownBy(() -> SalaryAccountValidator.normalize(bank, "1002", "예금주")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("null 입력·공백만 있는 예금주·101자 예금주는 거절")
	void rejectsNullsAndHolder() {
		assertThatThrownBy(() -> SalaryAccountValidator.normalize(null, "1002", "예금주")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", null, "예금주")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", "1002", null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", "1002", " 　 ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> SalaryAccountValidator.normalize("020", "1002", "가".repeat(101))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("예금주명은 앞뒤 공백만 제거하고 내부 공백·대소문자·외국어 이름을 유지 (100자 경계 허용)")
	void holderStrip() {
		assertThat(SalaryAccountValidator.normalize("020", "1002", "  Nguyen  Van A 　").accountHolderName()).isEqualTo("Nguyen  Van A");
		assertThat(SalaryAccountValidator.normalize("020", "1002", "가".repeat(100)).accountHolderName()).hasSize(100);
	}

	@Test
	@DisplayName("정규화 결과 toString은 번호를 가린다")
	void redacted() {
		var account = SalaryAccountValidator.normalize("020", "1002-0030-0400", "예금주");
		assertThat(account.toString()).isEqualTo("NormalizedAccount[redacted]").doesNotContain("100200300400");
	}
}

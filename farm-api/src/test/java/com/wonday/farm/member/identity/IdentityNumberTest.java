package com.wonday.farm.member.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// 문서 규칙: 공백·하이픈(-) 제거 후 ASCII 숫자 13자리만 허용. 체크섬·생년월일·국적 추정은 요구하지 않는다.
@DisplayName("식별번호 정규화 (IdentityNumber)·국적별 번호 종류")
class IdentityNumberTest {

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"1111111111111", "111111-1111111", " 111111-1111111 ", "111111 1111111", "1-1-1-1-1-1-1-1-1-1-1-1-1",
		"111111\t1111111", "111111 1111111", "111111　1111111", "--1111111111111--"})
	@DisplayName("ASCII 하이픈과 (유니코드) 공백만 제거하고 13자리 숫자로 정규화한다")
	void normalizes(String raw) {
		assertThat(IdentityNumber.normalize(raw)).isEqualTo("1111111111111");
	}

	@Test
	@DisplayName("생년월일·체크섬으로 보이지 않는 번호(0 13개, 9 13개)도 형식만 맞으면 허용한다")
	void noChecksumOrBirthDateRule() {
		assertThat(IdentityNumber.normalize("0000000000000")).isEqualTo("0000000000000");
		assertThat(IdentityNumber.normalize("999999-9999999")).isEqualTo("9999999999999");
	}

	@ParameterizedTest(name = "\"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "-", "111111111111", "11111111111111", "111111-111111a", "１１１１１１１１１１１１１",
		"111111_1111111", "111111.1111111", "111111–1111111", "111111—1111111", "+1111111111111", "١١١١١١١١١١١١١",
		"111111​1111111"})
	@DisplayName("null·빈 값·자리 수 오류·숫자 이외·전각/비ASCII 숫자·하이픈 외 대시·폭 없는 공백은 거절")
	void rejects(String raw) {
		assertThatThrownBy(() -> IdentityNumber.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("100자를 넘는 입력은 정규화 전에 거절한다 (101자, 하이픈 87개 + 숫자 13개 + 1)")
	void overlongRejected() {
		String hundred = "-".repeat(87) + "1111111111111";
		assertThat(hundred).hasSize(100);
		assertThat(IdentityNumber.normalize(hundred)).isEqualTo("1111111111111");
		assertThatThrownBy(() -> IdentityNumber.normalize("-" + hundred)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> IdentityNumber.normalize("1".repeat(100_000))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("오류 메시지에 입력 번호가 들어가지 않는다")
	void messageHasNoNumber() {
		assertThatThrownBy(() -> IdentityNumber.normalize("123456-789012x"))
			.hasMessageNotContaining("123456").hasMessageNotContaining("789012");
	}

	@Test
	@DisplayName("DOMESTIC → RESIDENT_REGISTRATION_NUMBER, FOREIGN → FOREIGN_REGISTRATION_NUMBER")
	void nationalityToIdentityType() {
		assertThat(NationalityType.DOMESTIC.identityType()).isEqualTo(IdentityType.RESIDENT_REGISTRATION_NUMBER);
		assertThat(NationalityType.FOREIGN.identityType()).isEqualTo(IdentityType.FOREIGN_REGISTRATION_NUMBER);
		assertThat(IdentityLookupMode.values()).containsExactly(IdentityLookupMode.LEGACY, IdentityLookupMode.ENCRYPTED);
	}
}

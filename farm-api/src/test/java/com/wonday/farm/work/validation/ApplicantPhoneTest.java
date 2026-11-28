package com.wonday.farm.work.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// Java 쪽 정규화(전체 연락처 응답)만 확인한다. 목록 마스킹·상태는 SQL에서 계산하므로 여기서 실행 검증하지 않는다.
@DisplayName("WORK-01 전체 연락처 정규화 (ApplicantPhone)")
class ApplicantPhoneTest {
	@ParameterizedTest
	@CsvSource({
		"01012345678, 01012345678",
		"010-1234-5678, 01012345678",
		"010 1234 5678, 01012345678",
		"' 010-1234-5678 ', 01012345678",
		"021234567, ''",
		"0212345678, 0212345678",
		"02-123-4567, ''",
		"031-123-4567, 0311234567"
	})
	@DisplayName("하이픈·공백 제거 후 숫자 10~11자리만 정상 번호")
	void normalized(String input, String expected) {
		assertThat(ApplicantPhone.normalized(input)).isEqualTo(expected.isEmpty() ? null : expected);
	}

	@Test
	@DisplayName("탭·줄바꿈·NBSP 같은 공백도 제거(Unicode 공백)")
	void unicodeSpace() {
		assertThat(ApplicantPhone.normalized("010\t1234\n5678")).isEqualTo("01012345678");
		assertThat(ApplicantPhone.normalized("010 1234 5678")).isEqualTo("01012345678");
	}

	@ParameterizedTest
	@ValueSource(strings = {"+821012345678", "010.1234.5678", "(010)12345678", "010123456789", "010123", "abc", "０１０１２３４５６７８",
		"010–1234–5678"})
	@DisplayName("국가번호·점·괄호·12자리 이상·9자리 이하·문자·전각 숫자·다른 대시는 INVALID_FORMAT, 번호는 null(보정·가상 번호 없음)")
	void invalid(String input) {
		assertThat(ApplicantPhone.normalized(input)).isNull();
		assertThat(ApplicantPhone.state(input)).isEqualTo("INVALID_FORMAT");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "   ", "-", "- -"})
	@DisplayName("빈 값·공백·하이픈만 있는 값은 NOT_REGISTERED")
	void blank(String input) {
		assertThat(ApplicantPhone.state(input)).isEqualTo("NOT_REGISTERED");
		assertThat(ApplicantPhone.normalized(input)).isNull();
	}

	@Test
	@DisplayName("null은 phone=null·NOT_REGISTERED, 정상 번호는 AVAILABLE")
	void states() {
		assertThat(ApplicantPhone.normalized(null)).isNull();
		assertThat(ApplicantPhone.state(null)).isEqualTo("NOT_REGISTERED");
		assertThat(ApplicantPhone.state("010-0000-1001")).isEqualTo("AVAILABLE");
		assertThat(ApplicantPhone.state("0212345678")).isEqualTo("AVAILABLE");
	}
}

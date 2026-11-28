package com.wonday.farm.work.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wonday.farm.work.dto.TermsVersionRequest;
import com.wonday.farm.work.exception.WorkSelectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WORK-02 SelectionInput 경로 ID·조건 버전")
class SelectionInputTest {
	private static void invalid(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(WorkSelectionException.class, e -> {
			assertThat(e.status().value()).isEqualTo(400);
			assertThat(e.code()).isEqualTo("INVALID_REQUEST");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"1", "501", "9223372036854775807"})
	@DisplayName("양수 10진 ID 허용 (Long 최대 포함)")
	void ids(String text) { assertThat(SelectionInput.id(text)).isEqualTo(Long.parseLong(text)); }

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "0", "-1", "+1", "01", "1.0", "1e3", " 1", "1 ", "abc", "９", "9223372036854775808", "12345678901234567890"})
	@DisplayName("0·음수·부호·앞자리 0·소수·지수·공백·전각 숫자·범위 초과 → INVALID_REQUEST")
	void badIds(String text) { invalid(() -> SelectionInput.id(text)); }

	@Test
	@DisplayName("require: 0 이하 ID 하나라도 있으면 INVALID_REQUEST")
	void require() {
		SelectionInput.require(1, 2, 3);
		invalid(() -> SelectionInput.require(1, 0));
		invalid(() -> SelectionInput.require(-5));
	}

	@Test
	@DisplayName("version: 양수만 허용, null 요청·null·0·음수 → INVALID_REQUEST")
	void version() {
		assertThat(SelectionInput.version(new TermsVersionRequest(3))).isEqualTo(3);
		assertThat(SelectionInput.version(new TermsVersionRequest(Integer.MAX_VALUE))).isEqualTo(Integer.MAX_VALUE);
		invalid(() -> SelectionInput.version(null));
		invalid(() -> SelectionInput.version(new TermsVersionRequest(null)));
		invalid(() -> SelectionInput.version(new TermsVersionRequest(0)));
		invalid(() -> SelectionInput.version(new TermsVersionRequest(-1)));
	}
}

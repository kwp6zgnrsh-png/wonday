package com.wonday.farm.application.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.wonday.farm.application.exception.MyApplicationQueryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("APP-04 커서 인코딩·검증")
class ApplicationHistoryCursorTest {
	private static String raw(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void invalid(String token, long worker, String scope, long schedule, String filter) {
		assertThatThrownBy(() -> ApplicationHistoryCursor.decode(token, worker, scope, schedule, filter))
			.isInstanceOfSatisfying(MyApplicationQueryException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
	}

	@Test
	@DisplayName("같은 계정·범위·일정·필터면 앵커 ID를 되돌리고, Base64URL(패딩 없음) 형식이다")
	void roundTrip() {
		String token = ApplicationHistoryCursor.encode(5, "LIST", 0, "PENDING", 501);
		assertThat(token).matches("[A-Za-z0-9_-]+");
		assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)).isEqualTo("1|5|LIST|0|PENDING|501");
		assertThat(ApplicationHistoryCursor.decode(token, 5, "LIST", 0, "PENDING")).isEqualTo(501L);
		assertThat(ApplicationHistoryCursor.decode(ApplicationHistoryCursor.encode(5, "HISTORY", 201, "ALL", Long.MAX_VALUE),
			5, "HISTORY", 201, "ALL")).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	@DisplayName("커서가 없으면 null(첫 페이지)")
	void absent() {
		assertThat(ApplicationHistoryCursor.decode(null, 5, "LIST", 0, "ALL")).isNull();
	}

	@Test
	@DisplayName("다른 계정·LIST/HISTORY·일정·필터의 커서는 INVALID_CURSOR")
	void boundScope() {
		String list = ApplicationHistoryCursor.encode(5, "LIST", 0, "PENDING", 501);
		invalid(list, 6, "LIST", 0, "PENDING");
		invalid(list, 5, "HISTORY", 0, "PENDING");
		invalid(list, 5, "LIST", 0, "ALL");
		String history = ApplicationHistoryCursor.encode(5, "HISTORY", 201, "ALL", 400);
		invalid(history, 5, "HISTORY", 202, "ALL");
		invalid(history, 5, "LIST", 0, "ALL");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "abc def", "abc+def", "abc/def", "abc=", "%41%42", "가나다"})
	@DisplayName("빈 값·공백·Base64URL 밖의 문자(+ / = 패딩 포함)는 INVALID_CURSOR")
	void badCharacters(String token) {
		invalid(token, 5, "LIST", 0, "ALL");
	}

	@Test
	@DisplayName("256자를 넘는 커서는 내용과 관계없이 INVALID_CURSOR, 256자 이하 형식 오류도 INVALID_CURSOR")
	void length() {
		invalid("A".repeat(257), 5, "LIST", 0, "ALL");
		invalid("A".repeat(256), 5, "LIST", 0, "ALL");
	}

	@Test
	@DisplayName("디코딩 불가(Base64 길이 오류)·구분자 개수 오류·버전 불일치는 INVALID_CURSOR")
	void structure() {
		invalid("A", 5, "LIST", 0, "ALL"); // 1글자 Base64는 디코딩 불가
		invalid(raw("1|5|LIST|0|ALL"), 5, "LIST", 0, "ALL");
		invalid(raw("1|5|LIST|0|ALL|501|x"), 5, "LIST", 0, "ALL");
		invalid(raw("2|5|LIST|0|ALL|501"), 5, "LIST", 0, "ALL");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "01", "1.5", "abc", "", "9223372036854775808", "99999999999999999999"})
	@DisplayName("앵커 ID가 양의 Long이 아니면(0·음수·앞자리 0·소수·문자·Long 범위 초과) INVALID_CURSOR")
	void anchorId(String anchor) {
		invalid(raw("1|5|LIST|0|ALL|" + anchor), 5, "LIST", 0, "ALL");
	}

	@Test
	@DisplayName("서버가 만든 것과 다른 표기(패딩 추가 등 비정규 인코딩)는 같은 내용이어도 거절")
	void canonical() {
		String token = ApplicationHistoryCursor.encode(5, "LIST", 0, "ALL", 501);
		String padded = Base64.getUrlEncoder().encodeToString("1|5|LIST|0|ALL|501".getBytes(StandardCharsets.UTF_8));
		if (!padded.equals(token)) invalid(padded, 5, "LIST", 0, "ALL");
		// 마지막 문자의 남는 비트만 다른 표기(같은 바이트로 디코딩됨)도 재인코딩 비교로 거절된다.
		char last = token.charAt(token.length() - 1);
		String alias = token.substring(0, token.length() - 1) + (char) (last + 1);
		byte[] decoded;
		try { decoded = Base64.getUrlDecoder().decode(alias); } catch (IllegalArgumentException e) { decoded = null; }
		if (decoded != null && new String(decoded, StandardCharsets.UTF_8).equals("1|5|LIST|0|ALL|501")) {
			invalid(alias, 5, "LIST", 0, "ALL");
		}
	}

	@Test
	@DisplayName("커서는 서명 토큰이 아니다: 같은 계정 값으로 만든 커서는 받아들인다(소유자 검사는 서비스가 따로 한다)")
	void notSigned() {
		assertThat(ApplicationHistoryCursor.decode(raw("1|5|LIST|0|ALL|777"), 5, "LIST", 0, "ALL")).isEqualTo(777L);
	}
}

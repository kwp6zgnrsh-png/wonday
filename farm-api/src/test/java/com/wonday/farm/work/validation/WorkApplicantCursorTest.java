package com.wonday.farm.work.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.wonday.farm.work.exception.WorkApplicantException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WORK-01 지원자 목록 커서")
class WorkApplicantCursorTest {
	private static String raw(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
	private static void invalid(String token, long employer, long posting, long schedule, String filter) {
		assertThatThrownBy(() -> WorkApplicantCursor.decode(token, employer, posting, schedule, filter))
			.isInstanceOfSatisfying(WorkApplicantException.class, e -> {
				assertThat(e.code()).isEqualTo("INVALID_CURSOR");
				assertThat(e.status().value()).isEqualTo(400);
			});
	}

	@Test
	@DisplayName("정상 인코딩·디코딩: 버전 1·고용주·공고·일정·필터·앵커, Base64URL 패딩 없음")
	void roundTrip() {
		String token = WorkApplicantCursor.encode(10, 101, 201, "PENDING", 501);
		assertThat(token).matches("[A-Za-z0-9_-]+");
		assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)).isEqualTo("1|10|101|201|PENDING|501");
		assertThat(WorkApplicantCursor.decode(token, 10, 101, 201, "PENDING")).isEqualTo(501L);
		assertThat(WorkApplicantCursor.decode(WorkApplicantCursor.encode(10, 101, 201, "ALL", Long.MAX_VALUE), 10, 101, 201, "ALL"))
			.isEqualTo(Long.MAX_VALUE);
	}

	@Test
	@DisplayName("커서 생략(null)은 첫 페이지")
	void absent() {
		assertThat(WorkApplicantCursor.decode(null, 10, 101, 201, "PENDING")).isNull();
	}

	@Test
	@DisplayName("다른 고용주·공고·일정·필터의 커서는 거부")
	void boundScope() {
		String token = WorkApplicantCursor.encode(10, 101, 201, "PENDING", 501);
		invalid(token, 11, 101, 201, "PENDING");
		invalid(token, 10, 102, 201, "PENDING");
		invalid(token, 10, 101, 202, "PENDING");
		invalid(token, 10, 101, 201, "ALL");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "a b", "ab+c", "ab/c", "abc=", "가나", "%41"})
	@DisplayName("빈 값·공백·Base64URL 밖 문자(+ / = 포함) 거부")
	void characters(String token) {
		invalid(token, 10, 101, 201, "PENDING");
	}

	@Test
	@DisplayName("256자 초과 거부, Base64 디코딩 불가·구분자 개수·버전 오류 거부")
	void structure() {
		invalid("A".repeat(257), 10, 101, 201, "PENDING");
		invalid("A", 10, 101, 201, "PENDING");
		invalid(raw("1|10|101|201|PENDING"), 10, 101, 201, "PENDING");
		invalid(raw("1|10|101|201|PENDING|501|x"), 10, 101, 201, "PENDING");
		invalid(raw("2|10|101|201|PENDING|501"), 10, 101, 201, "PENDING");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "007", "1.0", "x", "", "9223372036854775808", "123456789012345678901"})
	@DisplayName("앵커 ID 0·음수·앞자리 0·소수·문자·빈 값·Long 범위 초과 거부")
	void anchor(String anchor) {
		invalid(raw("1|10|101|201|PENDING|" + anchor), 10, 101, 201, "PENDING");
	}

	@Test
	@DisplayName("서명 토큰이 아니다: 같은 범위 값으로 직접 만든 커서는 형식상 통과(앵커 소유 확인은 서비스·저장소 몫)")
	void notAuthentication() {
		assertThat(WorkApplicantCursor.decode(raw("1|10|101|201|PENDING|999"), 10, 101, 201, "PENDING")).isEqualTo(999L);
	}
}

package com.wonday.farm.member.signup.dto;

import static com.wonday.farm.member.signup.testsupport.SignupTestData.account;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.body;
import static com.wonday.farm.member.signup.testsupport.SignupTestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 실제 SignupRequest·SignupSalaryAccountRequest를 기본 JsonMapper로 읽고 Bean Validation을 별도로 확인한다.
// 역직렬화 성공은 필수값·길이 통과를 뜻하지 않는다. 서비스 단계 정규화는 SignupServiceTest가 맡는다.
@DisplayName("회원가입 요청 JSON·Bean Validation")
class SignupRequestJsonTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();
	private static final List<String> TEXT_FIELDS = List.of("loginId", "password", "name", "phone", "role", "nationalityType", "identityNumber");
	private static final List<String> ACCOUNT_FIELDS = List.of("bankCode", "accountNumber", "accountHolderName");

	@AfterAll
	static void close() {
		FACTORY.close();
	}

	@Nested
	@DisplayName("JSON 문자열만 허용")
	class Strings {
		@Test
		@DisplayName("정상 본문은 원문 문자열 그대로 읽고(앞자리 0 포함) 위반이 없다")
		void valid() {
			SignupRequest request = read(body());

			assertThat(request.identityNumber()).isEqualTo("111111-1111111");
			assertThat(request.salaryAccount().bankCode()).isEqualTo("020");
			assertThat(request.salaryAccount().accountNumber()).isEqualTo("1002-0030-0400");
			assertThat(VALIDATOR.validate(request)).isEmpty();
		}

		@ParameterizedTest(name = "값 {0}")
		@ValueSource(strings = {"1", "0", "1.5", "true", "false", "[]", "[\"WORKER\"]", "{}", "{\"v\":\"x\"}"})
		@DisplayName("모든 문자 필드(회원 7개·계좌 3개)에서 숫자·boolean·배열·객체를 문자열로 자동 변환하지 않는다")
		void nonStringRejected(String raw) {
			for (String field : TEXT_FIELDS) {
				assertThatThrownBy(() -> readRaw(replace(json(body()), "\"" + field + "\":", raw, field)))
					.as(field).isInstanceOf(JacksonException.class);
			}
			for (String field : ACCOUNT_FIELDS) {
				assertThatThrownBy(() -> readRaw(replace(json(body()), "\"" + field + "\":", raw, field)))
					.as(field).isInstanceOf(JacksonException.class);
			}
		}

		@Test
		@DisplayName("숫자로 보낸 은행 코드 20·식별번호 정수는 0이 사라지지 않도록 거절된다")
		void numericCodesRejected() {
			Map<String, Object> body = body();
			account(body).put("bankCode", 20);
			assertThatThrownBy(() -> read(body)).isInstanceOf(JacksonException.class);
			Map<String, Object> identity = body();
			identity.put("identityNumber", 1111111111111L);
			assertThatThrownBy(() -> read(identity)).isInstanceOf(JacksonException.class);
		}

		@Test
		@DisplayName("toString은 비밀번호·번호·계좌를 가린다")
		void redacted() {
			SignupRequest request = read(body());

			assertThat(request.toString()).isEqualTo("SignupRequest[redacted]");
			assertThat(request.salaryAccount().toString()).isEqualTo("SignupSalaryAccountRequest[redacted]");
		}
	}

	@Nested
	@DisplayName("필수값·길이 (Bean Validation)")
	class Required {
		@Test
		@DisplayName("회원 필드는 누락·null·빈 값·공백만 있는 값이 모두 해당 필드 위반")
		void missingNullBlank() {
			for (String field : TEXT_FIELDS) {
				for (Object value : new Object[] {null, "", "   "}) {
					Map<String, Object> body = body();
					body.put(field, value);
					assertThat(paths(VALIDATOR.validate(read(body)))).as(field + "=" + value).containsExactly(field);
				}
				Map<String, Object> missing = body();
				missing.remove(field);
				assertThat(paths(VALIDATOR.validate(read(missing)))).as(field + " missing").containsExactly(field);
			}
		}

		@Test
		@DisplayName("salaryAccount 생략·null은 허용, 객체를 보내면 세 필드 모두 필수 ({}·일부 누락·공백 거절)")
		void optionalAccount() {
			Map<String, Object> omitted = body();
			omitted.remove("salaryAccount");
			assertThat(read(omitted).salaryAccount()).isNull();
			assertThat(VALIDATOR.validate(read(omitted))).isEmpty();
			Map<String, Object> nullAccount = body();
			nullAccount.put("salaryAccount", null);
			assertThat(VALIDATOR.validate(read(nullAccount))).isEmpty();

			Map<String, Object> empty = body();
			empty.put("salaryAccount", Map.of());
			assertThat(paths(VALIDATOR.validate(read(empty)))).containsExactlyInAnyOrder(
				"salaryAccount.bankCode", "salaryAccount.accountNumber", "salaryAccount.accountHolderName");
			for (String field : ACCOUNT_FIELDS) {
				Map<String, Object> partial = body();
				account(partial).remove(field);
				assertThat(paths(VALIDATOR.validate(read(partial)))).as(field).containsExactly("salaryAccount." + field);
				Map<String, Object> blank = body();
				account(blank).put(field, " ");
				assertThat(paths(VALIDATOR.validate(read(blank)))).as(field + " blank").containsExactly("salaryAccount." + field);
			}
		}

		@Test
		@DisplayName("salaryAccount에 문자열·배열을 보내면 역직렬화 단계에서 거절")
		void accountShape() {
			for (String raw : new String[] {"\"020-1002\"", "[]", "true", "1"}) {
				assertThatThrownBy(() -> readRaw(replace(json(body()), "\"salaryAccount\":", raw, "salaryAccount")))
					.as(raw).isInstanceOf(JacksonException.class);
			}
		}

		@Test
		@DisplayName("원문 길이 상한: loginId·name·phone·identityNumber·accountNumber·holder 100자, password 72자, role 32, nationality 16, bankCode 3")
		void lengthLimits() {
			Map<String, Integer> limits = Map.of("loginId", 100, "name", 100, "phone", 100, "identityNumber", 100,
				"password", 72, "role", 32, "nationalityType", 16);
			limits.forEach((field, limit) -> {
				Map<String, Object> atLimit = body();
				atLimit.put(field, "a".repeat(limit));
				assertThat(paths(VALIDATOR.validate(read(atLimit)))).as(field + " at limit").doesNotContain(field);
				Map<String, Object> over = body();
				over.put(field, "a".repeat(limit + 1));
				assertThat(paths(VALIDATOR.validate(read(over)))).as(field + " over").containsExactly(field);
			});
			Map<String, Integer> accountLimits = Map.of("bankCode", 3, "accountNumber", 100, "accountHolderName", 100);
			accountLimits.forEach((field, limit) -> {
				Map<String, Object> over = body();
				account(over).put(field, "1".repeat(limit + 1));
				assertThat(paths(VALIDATOR.validate(read(over)))).as(field).containsExactly("salaryAccount." + field);
			});
		}

		@Test
		@DisplayName("요청 DTO에는 회원 ID·해시·식별번호 종류 필드가 없다 (서버가 결정)")
		void noServerOwnedFields() {
			assertThat(java.util.Arrays.stream(SignupRequest.class.getRecordComponents()).map(c -> c.getName()))
				.containsExactly("loginId", "password", "name", "phone", "role", "nationalityType", "identityNumber", "salaryAccount");
		}
	}

	private static SignupRequest read(Map<String, Object> body) {
		return readRaw(json(body));
	}

	private static SignupRequest readRaw(String json) {
		return JSON.readValue(json, SignupRequest.class);
	}

	// 지정 필드의 JSON 값을 원문 토큰으로 교체한다 (필드 값은 모두 따옴표 문자열 또는 객체).
	private static String replace(String json, String key, String raw, String field) {
		int start = json.indexOf(key) + key.length();
		int end;
		if (json.charAt(start) == '{') {
			end = json.indexOf('}', start) + 1;
		} else {
			end = json.indexOf('"', start + 1) + 1;
		}
		return json.substring(0, start) + raw + json.substring(end);
	}

	private static List<String> paths(Set<? extends ConstraintViolation<?>> violations) {
		return violations.stream().map(violation -> violation.getPropertyPath().toString()).toList();
	}
}

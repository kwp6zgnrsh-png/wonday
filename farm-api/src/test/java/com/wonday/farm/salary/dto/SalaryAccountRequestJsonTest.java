package com.wonday.farm.salary.dto;

import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.JSON;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.body;
import static com.wonday.farm.salary.testsupport.SalaryAccountTestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

// 프로젝트의 Jackson 3 기본 JsonMapper + Hibernate Validator로 DTO만 본다. Spring Boot가 구성한 JsonMapper 설정은 없다.
// 형식 규칙(은행 3자리·숫자 1~30자리·예금주 공백 제거)은 기존 SalaryAccountValidatorTest와 서비스 테스트에서 본다.
@DisplayName("급여 계좌 PUT 요청 JSON·DTO 검증")
class SalaryAccountRequestJsonTest {
	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void open() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void close() {
		factory.close();
	}

	private static SalaryAccountRequest read(String raw) {
		return JSON.readValue(raw, SalaryAccountRequest.class);
	}

	private static Set<String> invalid(SalaryAccountRequest request) {
		return validator.validate(request).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
			.collect(Collectors.toSet());
	}

	@Test
	@DisplayName("문서 예시는 원문 그대로(하이픈·앞자리 0 포함) 들어오고 DTO 검증을 통과한다")
	void example() {
		Map<String, Object> body = body(0);
		body.put("bankCode", "004");
		body.put("accountNumber", "0012-3456");
		var request = read(json(body));
		assertThat(request).isEqualTo(new SalaryAccountRequest("004", "0012-3456", "TEST WORKER", 0L));
		assertThat(invalid(request)).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"bankCode", "accountNumber", "accountHolderName"})
	@DisplayName("문자열 필드에 숫자·boolean·배열·객체를 보내면 역직렬화 실패 (앞자리 0 손실·자동 변환 방지)")
	void stringOnly(String field) {
		for (Object value : new Object[] {20, 100200300400L, true, List.of("020"), Map.of("v", "020"), 1.5}) {
			Map<String, Object> body = body(0);
			body.put(field, value);
			assertThatThrownBy(() -> read(json(body))).as(field + "=" + value).isInstanceOf(JacksonException.class);
		}
	}

	@Test
	@DisplayName("version: 0·Long 최대값은 JSON 정수로 받는다")
	void versionAccepted() {
		assertThat(read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":0}").version()).isZero();
		var max = read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":9223372036854775807}");
		assertThat(max.version()).isEqualTo(Long.MAX_VALUE);
		assertThat(invalid(max)).isEmpty();
	}

	@ParameterizedTest(name = "version {0}")
	@ValueSource(strings = {"1.0", "1.5", "\"1\"", "true", "[1]", "{\"v\":1}", "1e2", "9223372036854775808", "-9223372036854775809"})
	@DisplayName("version: 소수·지수·숫자 문자열·boolean·배열·객체·Long 범위 초과는 역직렬화 실패")
	void versionRejectedAtJson(String raw) {
		assertThatThrownBy(() -> read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":" + raw + "}"))
			.isInstanceOf(JacksonException.class);
	}

	@Test
	@DisplayName("version: 음수·null·누락은 DTO 검증 오류")
	void versionRejectedAtValidation() {
		assertThat(invalid(read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":-1}")))
			.containsExactly("version");
		assertThat(invalid(read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\",\"version\":null}")))
			.containsExactly("version");
		assertThat(invalid(read("{\"bankCode\":\"020\",\"accountNumber\":\"1\",\"accountHolderName\":\"A\"}"))).containsExactly("version");
	}

	@Test
	@DisplayName("세 문자열 필드의 누락·null·빈 값·공백은 각 필드 오류, 길이 초과(은행 4자·번호 101자·예금주 101자)도 오류")
	void requiredAndLength() {
		for (String field : new String[] {"bankCode", "accountNumber", "accountHolderName"}) {
			for (Object value : new Object[] {null, "", "   "}) {
				Map<String, Object> body = body(0);
				body.put(field, value);
				assertThat(invalid(read(json(body)))).as(field + "=" + value).containsExactly(field);
			}
			Map<String, Object> missing = body(0);
			missing.remove(field);
			assertThat(invalid(read(json(missing)))).as(field + " 누락").containsExactly(field);
		}
		assertThat(invalid(new SalaryAccountRequest("0200", "1".repeat(101), "가".repeat(101), 0L)))
			.containsExactlyInAnyOrder("bankCode", "accountNumber", "accountHolderName");
		assertThat(invalid(new SalaryAccountRequest("020", "1".repeat(100), "가".repeat(100), 0L))).isEmpty();
	}

	@Test
	@DisplayName("요청·응답 toString은 번호·예금주를 가린다. 미등록 응답 JSON은 null 필드와 version을 명시한다")
	void redactionAndResponseShape() {
		assertThat(new SalaryAccountRequest("020", "100200300400", "TEST WORKER", 3L).toString()).isEqualTo("SalaryAccountRequest[redacted]");
		var registered = new SalaryAccountResponse(true, "020", "********0400", "TEST WORKER", 1L);
		assertThat(registered.toString()).isEqualTo("SalaryAccountResponse[redacted]");

		var node = JSON.readTree(JSON.writeValueAsString(new SalaryAccountResponse(false, null, null, null, 4L)));
		assertThat(node.propertyNames()).containsExactlyInAnyOrder("registered", "bankCode", "maskedAccountNumber", "accountHolderName", "version");
		assertThat(node.get("bankCode").isNull()).isTrue();
		assertThat(node.get("maskedAccountNumber").isNull()).isTrue();
		assertThat(node.get("accountHolderName").isNull()).isTrue();
		assertThat(node.get("version").isIntegralNumber()).isTrue();
		assertThat(node.get("version").asLong()).isEqualTo(4L);
	}
}

package com.wonday.farm.insurance.dto;

import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.JSON;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.body;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData;
import com.wonday.farm.member.identity.NationalityType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DatabindException;

// 프로젝트 의존성의 Jackson 3(tools.jackson) 기본 JsonMapper와 Hibernate Validator로 DTO만 검증한다.
// Spring Boot가 구성한 JsonMapper 설정(모듈·기능 플래그)은 이 테스트에 없다.
@DisplayName("보험 V2 요청·응답 DTO")
class WorkerVerificationV2RequestTest {
	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	private static WorkerVerificationV2Request read(String raw) {
		return JSON.readValue(raw, WorkerVerificationV2Request.class);
	}

	private static Set<String> invalidFields(WorkerVerificationV2Request request) {
		return validator.validate(request).stream().map(ConstraintViolation::getPropertyPath).map(Object::toString)
			.collect(Collectors.toSet());
	}

	@Nested
	@DisplayName("JSON 문자열 강제")
	class Strings {
		@Test
		@DisplayName("문서 예시 JSON은 원문 그대로 네 필드에 들어가고 검증을 통과한다")
		void example() {
			var request = read(json(body()));

			assertThat(request).isEqualTo(new WorkerVerificationV2Request("TEST WORKER", "010-1234-5678", "FOREIGN", "900101-5000000"));
			assertThat(invalidFields(request)).isEmpty();
		}

		@Test
		@DisplayName("앞자리 0 식별번호·전화는 문자열로 보존된다")
		void leadingZero() {
			Map<String, Object> body = body();
			body.put("identityNumber", "0001010000000");
			body.put("phone", "01000000000");

			var request = read(json(body));
			assertThat(request.identityNumber()).isEqualTo("0001010000000");
			assertThat(request.phone()).isEqualTo("01000000000");
		}

		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"name", "phone", "nationalityType", "identityNumber"})
		@DisplayName("각 필드에 숫자·boolean·배열·객체를 보내면 역직렬화가 실패한다")
		void nonStringRejected(String field) {
			for (Object value : new Object[] {1012345678L, 9001015000000L, true, java.util.List.of("FOREIGN"), Map.of("v", "x"), 1.5}) {
				Map<String, Object> body = body();
				body.put(field, value);
				assertThatThrownBy(() -> read(json(body))).as(field + "=" + value).isInstanceOf(DatabindException.class);
			}
		}

		@Test
		@DisplayName("JSON null은 null로 들어오고 @NotBlank로 거절된다")
		void nullValues() {
			for (String field : new String[] {"name", "phone", "nationalityType", "identityNumber"}) {
				Map<String, Object> body = body();
				body.put(field, null);
				assertThat(invalidFields(read(json(body)))).as(field).containsExactly(field);
			}
		}
	}

	@Nested
	@DisplayName("필수·길이·국적 검증")
	class Constraints {
		@Test
		@DisplayName("필드 누락은 각 필드 오류")
		void missing() {
			for (String field : new String[] {"name", "phone", "nationalityType", "identityNumber"}) {
				Map<String, Object> body = body();
				body.remove(field);
				assertThat(invalidFields(read(json(body)))).as(field).containsExactly(field);
			}
		}

		@Test
		@DisplayName("빈 문자열·공백만은 @NotBlank 오류")
		void blank() {
			// U+3000 같은 유니코드 공백은 Validator 구현에 따라 다를 수 있어 서비스·컨트롤러 테스트에서 400을 본다.
			for (String value : new String[] {"", " ", "\t", "  \n "}) {
				assertThat(invalidFields(new WorkerVerificationV2Request(value, value, value, value)))
					.as("[" + value + "]").contains("name", "phone", "identityNumber", "nationalityType");
			}
		}

		@Test
		@DisplayName("name·phone·identityNumber는 100자까지 허용, 101자 거절")
		void length() {
			String max = "1".repeat(100);
			String over = "1".repeat(101);
			assertThat(invalidFields(new WorkerVerificationV2Request(max, max, "DOMESTIC", max))).isEmpty();
			assertThat(invalidFields(new WorkerVerificationV2Request(over, over, "DOMESTIC", over)))
				.containsExactlyInAnyOrder("name", "phone", "identityNumber");
		}

		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"domestic", "Foreign", " FOREIGN", "FOREIGN ", "KOREAN", "DOMESTICFOREIGN", "DOMESTIC|FOREIGN",
			"RESIDENT_REGISTRATION_NUMBER", "0"})
		@DisplayName("nationalityType은 정확히 DOMESTIC 또는 FOREIGN만")
		void nationality(String value) {
			assertThat(invalidFields(new WorkerVerificationV2Request("A", "01012345678", value, "1111111111111")))
				.containsExactly("nationalityType");
		}

		@Test
		@DisplayName("DOMESTIC·FOREIGN 정확한 값은 통과한다")
		void nationalityValid() {
			for (NationalityType type : NationalityType.values()) {
				assertThat(invalidFields(new WorkerVerificationV2Request("A", "01012345678", type.name(), "1111111111111"))).isEmpty();
			}
		}

		@Test
		@DisplayName("V1의 residentRegistrationNumber만 보내면 identityNumber 누락으로 거절된다")
		void v1FieldOnly() {
			Map<String, Object> body = body();
			body.remove("identityNumber");
			body.put("residentRegistrationNumber", "111111-1111111");

			WorkerVerificationV2Request request;
			try {
				request = read(json(body));
			} catch (DatabindException unknownProperty) {
				// 알 수 없는 필드를 거절하는 설정이어도 결과적으로 거절이다.
				return;
			}
			assertThat(request.identityNumber()).isNull();
			assertThat(invalidFields(request)).containsExactly("identityNumber");
		}
	}

	@Nested
	@DisplayName("toString 비노출")
	class Redaction {
		@Test
		@DisplayName("요청 toString에 이름·전화·국적·번호가 없다")
		void request() {
			String text = WorkerVerificationV2TestData.foreign().toString();
			assertThat(text).isEqualTo("WorkerVerificationV2Request[redacted]");
		}

		@Test
		@DisplayName("응답 toString에 workerId·이름·전화가 없고, JSON 직렬화는 네 필드만 낸다")
		void response() throws Exception {
			var response = new VerifiedWorkerV2Response(101L, "TEST WORKER", "01012345678", NationalityType.FOREIGN);
			assertThat(response.toString()).isEqualTo("VerifiedWorkerV2Response[redacted]");

			var node = JSON.readTree(JSON.writeValueAsString(response));
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("workerId", "name", "phone", "nationalityType");
			assertThat(node.get("workerId").isNumber()).isTrue();
			assertThat(node.get("nationalityType").asString()).isEqualTo("FOREIGN");
		}
	}
}

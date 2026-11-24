package com.wonday.farm.insurance.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 요청 DTO의 정규화(compact constructor), 문자열 전용 역직렬화, Bean Validation, toString 가림을 Spring 없이 확인한다.
// 값은 모두 시연용 가상 번호다. 실제 HTTP 400 변환은 InsuranceIntegrationApiTest(standalone MockMvc)에서 본다.
class WorkerVerificationRequestTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final jakarta.validation.ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();
	private static final String RRN = "000000-0000000";

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	@Nested
	class Normalization {
		@Test
		void stripsLeadingAndTrailingWhitespaceFromNameOnly() {
			var request = new WorkerVerificationRequest("  데모 근로자\t\n", "01000000000", "0000000000000");

			assertThat(request.name()).isEqualTo("데모 근로자");
		}

		@Test
		void removesHyphensAndWhitespaceAnywhereInPhoneAndResidentNumber() {
			var request = new WorkerVerificationRequest("데모", " 010-0000 0000\t", " 000000 - 0000000 ");

			assertThat(request.phone()).isEqualTo("01000000000");
			assertThat(request.residentRegistrationNumber()).isEqualTo("0000000000000");
		}

		@Test
		void removesUnicodeWhitespaceSuchAsIdeographicSpace() {
			var request = new WorkerVerificationRequest("데모", "010　0000　0000", "000000 0000000");

			assertThat(request.phone()).isEqualTo("01000000000");
			assertThat(request.residentRegistrationNumber()).isEqualTo("0000000000000");
		}

		@Test
		void keepsLeadingZerosAndDoesNotRemoveOtherCharacters() {
			var request = new WorkerVerificationRequest("데모", "(010)0000.0000", "000000_0000000");

			assertThat(request.phone()).isEqualTo("(010)0000.0000");
			assertThat(request.residentRegistrationNumber()).isEqualTo("000000_0000000");
		}

		@Test
		void nullValuesStayNull() {
			var request = new WorkerVerificationRequest(null, null, null);

			assertThat(request.name()).isNull();
			assertThat(request.phone()).isNull();
			assertThat(request.residentRegistrationNumber()).isNull();
		}
	}

	@Nested
	class JsonStringsOnly {
		@Test
		void readsAndNormalizesStringValues() {
			var request = read("\" 데모 \"", "\"010-0000-0000\"", "\"" + RRN + "\"");

			assertThat(request.name()).isEqualTo("데모");
			assertThat(request.phone()).isEqualTo("01000000000");
			assertThat(request.residentRegistrationNumber()).isEqualTo("0000000000000");
		}

		@ParameterizedTest(name = "phone={0}")
		@ValueSource(strings = {"1000000000", "01000000000.0", "1e10", "true", "[\"01000000000\"]", "{\"v\":\"01000000000\"}"})
		void phoneRejectsNonStringJson(String value) {
			assertThatThrownBy(() -> read("\"데모\"", value, "\"" + RRN + "\"")).isInstanceOf(JacksonException.class);
		}

		@ParameterizedTest(name = "residentRegistrationNumber={0}")
		@ValueSource(strings = {"0", "1234567890123", "false", "[]", "{}"})
		void residentNumberRejectsNonStringJson(String value) {
			assertThatThrownBy(() -> read("\"데모\"", "\"01000000000\"", value)).isInstanceOf(JacksonException.class);
		}

		@ParameterizedTest(name = "name={0}")
		@ValueSource(strings = {"1", "true", "[\"데모\"]", "{\"first\":\"데모\"}"})
		void nameRejectsNonStringJson(String value) {
			assertThatThrownBy(() -> read(value, "\"01000000000\"", "\"" + RRN + "\"")).isInstanceOf(JacksonException.class);
		}

		@Test
		void jsonNullAndMissingFieldsAreLeftToBeanValidation() throws Exception {
			var request = JSON.readValue("{\"name\":null}", WorkerVerificationRequest.class);

			assertThat(request.name()).isNull();
			assertThat(request.phone()).isNull();
			assertThat(violatedFields(request)).containsExactlyInAnyOrder("name", "phone", "residentRegistrationNumber");
		}
	}

	@Nested
	class BeanValidation {
		@Test
		void validRequestHasNoViolations() {
			assertThat(VALIDATOR.validate(new WorkerVerificationRequest("데모", "010-0000-0000", RRN))).isEmpty();
		}

		@Test
		void valuesThatBecomeEmptyAfterNormalizationAreBlank() {
			var request = new WorkerVerificationRequest("   ", " - - ", "\t-\t");

			assertThat(violatedFields(request)).containsExactlyInAnyOrder("name", "phone", "residentRegistrationNumber");
		}

		@Test
		void nameOf100CharactersIsAllowedAnd101IsRejected() {
			assertThat(violatedFields(new WorkerVerificationRequest("가".repeat(100), "01000000000", RRN))).isEmpty();
			assertThat(violatedFields(new WorkerVerificationRequest("가".repeat(101), "01000000000", RRN)))
				.containsExactly("name");
		}

		@Test
		void sizeIsCheckedAfterHyphensAreRemoved() {
			// 하이픈 포함 101자라도 정규화 후 100자 이하라면 @Size는 통과한다. 자릿수 검사는 서비스가 맡는다.
			String phone = "0".repeat(100) + "-";

			assertThat(violatedFields(new WorkerVerificationRequest("데모", phone, RRN))).isEmpty();
		}
	}

	@Nested
	class Redaction {
		@Test
		void requestToStringHidesAllPersonalValues() {
			var request = new WorkerVerificationRequest("데모 근로자", "010-1234-5678", "900101-1234567");

			assertThat(request.toString()).isEqualTo("WorkerVerificationRequest[redacted]")
				.doesNotContain("데모").doesNotContain("01012345678").doesNotContain("9001011234567");
		}

		@Test
		void responseToStringHidesNameAndPhone() {
			var response = new VerifiedWorkerResponse(7L, "데모 근로자", "01012345678");

			assertThat(response.toString()).isEqualTo("VerifiedWorkerResponse[redacted]")
				.doesNotContain("데모").doesNotContain("01012345678");
		}

		@Test
		void responseHasNoResidentNumberOrPerFieldMatchComponent() {
			assertThat(VerifiedWorkerResponse.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.containsExactly("workerId", "name", "phone");
		}

		@Test
		void requestValidationMessagesDoNotCarryTheRejectedValue() {
			var violations = VALIDATOR.validate(new WorkerVerificationRequest("가".repeat(101), "01000000000", RRN));

			assertThat(violations).extracting(ConstraintViolation::getMessage)
				.allSatisfy(message -> assertThat(message).doesNotContain("가가"));
		}
	}

	private static WorkerVerificationRequest read(String name, String phone, String residentNumber) {
		return JSON.readValue("{\"name\":" + name + ",\"phone\":" + phone + ",\"residentRegistrationNumber\":"
			+ residentNumber + "}", WorkerVerificationRequest.class);
	}

	private static Set<String> violatedFields(WorkerVerificationRequest request) {
		return VALIDATOR.validate(request).stream()
			.map(violation -> violation.getPropertyPath().toString())
			.collect(java.util.stream.Collectors.toSet());
	}
}

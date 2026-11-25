package com.wonday.farm.job.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

// 모집 취소 요청 DTO에 붙은 운영 StrictIntegerDeserializer와 Bean Validation을 Spring 없이 실행한다.
class CancelWorkScheduleRequestTest {
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	private static final Validator VALIDATOR = FACTORY.getValidator();

	@AfterAll
	static void close() {
		FACTORY.close();
	}

	@ParameterizedTest(name = "expectedTermsVersion={0}")
	@ValueSource(strings = {"1", "2147483647"})
	void positiveJsonIntegerIsValid(String value) {
		var request = read("{\"expectedTermsVersion\":" + value + "}");

		assertThat(request.expectedTermsVersion()).isEqualTo(Integer.parseInt(value));
		assertThat(VALIDATOR.validate(request)).isEmpty();
	}

	@ParameterizedTest(name = "expectedTermsVersion={0}")
	@ValueSource(strings = {"1.5", "1.0", "1e0", "1E2", "\"1\"", "\"\"", "[1]", "{}", "{\"v\":1}", "true", "false",
		"2147483648", "-2147483649"})
	void nonIntegerOrOutOfRangeIsRejectedByTheDeserializer(String value) {
		assertThatThrownBy(() -> read("{\"expectedTermsVersion\":" + value + "}")).as(value)
			.isInstanceOf(JacksonException.class);
	}

	@ParameterizedTest(name = "expectedTermsVersion={0}")
	@ValueSource(strings = {"0", "-1", "-2147483648", "null"})
	void zeroNegativeOrNullIsAViolationOnExpectedTermsVersion(String value) {
		assertOnlyVersionViolated(read("{\"expectedTermsVersion\":" + value + "}"));
	}

	@Test
	void missingVersionIsAViolation() {
		assertOnlyVersionViolated(read("{}"));
	}

	@Test
	void unknownPropertiesLikeStatusOrEmployerIdCannotBeSupplied() {
		// 소유자·상태·취소 시각은 클라이언트가 지정하지 않는다. 레코드에 해당 구성 요소가 없다.
		assertThat(CancelWorkScheduleRequest.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName).containsExactly("expectedTermsVersion");
	}

	private static void assertOnlyVersionViolated(CancelWorkScheduleRequest request) {
		assertThat(VALIDATOR.validate(request)).extracting(ConstraintViolation::getPropertyPath)
			.extracting(Object::toString).containsExactly("expectedTermsVersion");
	}

	private static CancelWorkScheduleRequest read(String json) {
		return JSON.readValue(json, CancelWorkScheduleRequest.class);
	}
}

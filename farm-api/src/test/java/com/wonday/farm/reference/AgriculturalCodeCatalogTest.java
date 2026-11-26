package com.wonday.farm.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

// 실제 classpath의 reference/*.json을 읽는다. 외부 Excel·Downloads 경로·인터넷에 의존하지 않는다.
@DisplayName("작목·공통 작업 코드 사전")
class AgriculturalCodeCatalogTest {
	private static final AgriculturalCodeCatalog CODES = new AgriculturalCodeCatalog();

	@Test
	@DisplayName("VC02100100100102는 노지가을배추, 0058은 수확이다")
	void knownCodes() {
		assertThat(CODES.cropName("VC02100100100102")).isEqualTo("노지가을배추");
		assertThat(CODES.workTypeName("0058")).isEqualTo("수확");
	}

	@ParameterizedTest(name = "workTypeCode=\"{0}\"")
	@ValueSource(strings = {"58", "058", "00058", " 0058", "0058 "})
	@DisplayName("작업 코드는 선행 0을 포함한 원문으로만 찾는다. 숫자 변환·공백 제거 같은 정규화를 하지 않는다")
	void workTypeCodeIsNotNormalized(String code) {
		assertThat(CODES.workTypeName(code)).isNull();
	}

	@ParameterizedTest(name = "cropCode=\"{0}\"")
	@ValueSource(strings = {"vc02100100100102", "VC0210010010010", "VC021001001001020", " VC02100100100102"})
	@DisplayName("작목 코드도 대소문자·길이·공백을 바꾼 값은 다른 코드로 본다")
	void cropCodeIsNotNormalized(String code) {
		assertThat(CODES.cropName(code)).isNull();
	}

	@ParameterizedTest(name = "code={0}")
	@NullSource
	@ValueSource(strings = {"", "UNKNOWN", "9999"})
	@DisplayName("미등록 코드와 null 코드의 이름은 null이다")
	void unknownOrNullCodes(String code) {
		assertThat(CODES.cropName(code)).isNull();
		assertThat(CODES.workTypeName(code)).isNull();
	}

	@Test
	@DisplayName("두 사전을 섞어 조회하지 않는다 (작업 코드를 작목 이름으로 찾지 않음)")
	void dictionariesAreSeparate() {
		assertThat(CODES.cropName("0058")).isNull();
		assertThat(CODES.workTypeName("VC02100100100102")).isNull();
	}

	@Test
	@DisplayName("모든 작업 코드는 4자리 숫자 문자열이고, 사전은 리소스의 항목 수(작목 242·작업 75)를 그대로 담는다")
	void resourceShape() throws Exception {
		Map<String, String> crops = raw("reference/crop-codes.json");
		Map<String, String> workTypes = raw("reference/work-type-codes.json");

		assertThat(crops).hasSize(242);
		assertThat(workTypes).hasSize(75);
		assertThat(workTypes.keySet()).allMatch(code -> code.matches("[0-9]{4}"));
		crops.forEach((code, name) -> assertThat(CODES.cropName(code)).as(code).isEqualTo(name));
		workTypes.forEach((code, name) -> assertThat(CODES.workTypeName(code)).as(code).isEqualTo(name));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> raw(String resource) throws Exception {
		try (InputStream input = new ClassPathResource(resource).getInputStream()) {
			return JsonMapper.builder().build().readValue(input, Map.class);
		}
	}
}

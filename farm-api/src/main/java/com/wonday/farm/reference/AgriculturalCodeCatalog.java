package com.wonday.farm.reference;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

// 사용자가 제공한 작목·공통 작업 코드다. 원본 엑셀 없이도 서버 JAR만으로 이름을 조회한다.
@Component
public class AgriculturalCodeCatalog {
	private final Map<String, String> crops;
	private final Map<String, String> workTypes;

	public AgriculturalCodeCatalog() {
		crops = load("reference/crop-codes.json");
		workTypes = load("reference/work-type-codes.json");
	}

	public String cropName(String code) { return code == null ? null : crops.get(code); }
	public String workTypeName(String code) { return code == null ? null : workTypes.get(code); }

	private static Map<String, String> load(String resource) {
		try (var input = new ClassPathResource(resource).getInputStream()) {
			Map<?, ?> raw = JsonMapper.builder().build().readValue(input, Map.class);
			var values = new HashMap<String, String>();
			for (var entry : raw.entrySet()) {
				if (!(entry.getKey() instanceof String code) || code.isBlank()
						|| !(entry.getValue() instanceof String name) || name.isBlank()) {
					throw new IllegalStateException("Invalid agricultural code resource: " + resource);
				}
				values.put(code, name);
			}
			return Map.copyOf(values);
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot read agricultural code resource: " + resource, exception);
		}
	}
}

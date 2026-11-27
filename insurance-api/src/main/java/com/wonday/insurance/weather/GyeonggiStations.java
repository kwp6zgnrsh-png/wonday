package com.wonday.insurance.weather;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class GyeonggiStations {
	private final Map<String, String> names;
	GyeonggiStations() throws java.io.IOException {
		var values = new LinkedHashMap<String, String>();
		try (var input = new ClassPathResource("weather/gyeonggi-stations.csv").getInputStream()) {
			new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).forEach(line -> {
				String[] pair = line.split(",", 2);
				if (pair.length != 2 || !pair[0].matches("[0-9A-Z]{10}") || pair[1].isBlank()
					|| values.putIfAbsent(pair[0], pair[1]) != null) throw new IllegalStateException("Invalid station scope");
			});
		}
		if (values.size() != 24) throw new IllegalStateException("Expected 24 Gyeonggi stations");
		names = java.util.Collections.unmodifiableMap(values);
	}
	Map<String, String> names() { return names; }
	void require(String code) {
		if (code == null || !names.containsKey(code)) throw new WeatherException(HttpStatus.BAD_REQUEST,
			"UNSUPPORTED_WEATHER_STATION", "Only configured Gyeonggi stations are supported");
	}
}

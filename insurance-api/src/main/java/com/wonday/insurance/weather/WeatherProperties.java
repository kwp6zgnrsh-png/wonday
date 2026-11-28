package com.wonday.insurance.weather;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("insurance.weather")
record WeatherProperties(String baseUrl, String serviceKey, Duration collectionRunTimeout) {
	@ConstructorBinding
	WeatherProperties {
		collectionRunTimeout = collectionRunTimeout == null ? Duration.ofMinutes(15) : collectionRunTimeout;
		if (collectionRunTimeout.compareTo(Duration.ofSeconds(1)) < 0 || collectionRunTimeout.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalArgumentException("Weather collection run timeout must be between one second and one day");
		}
	}
	WeatherProperties(String baseUrl, String serviceKey) {
		this(baseUrl, serviceKey, null);
	}
	@Override public String toString() { return "WeatherProperties[credentials=redacted]"; }
}

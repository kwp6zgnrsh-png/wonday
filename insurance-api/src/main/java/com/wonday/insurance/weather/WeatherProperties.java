package com.wonday.insurance.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("insurance.weather")
record WeatherProperties(String baseUrl, String serviceKey, String stationServiceKey) {
	@Override public String toString() { return "WeatherProperties[credentials=redacted]"; }
}

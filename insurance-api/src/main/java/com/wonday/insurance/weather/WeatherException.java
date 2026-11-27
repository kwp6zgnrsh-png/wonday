package com.wonday.insurance.weather;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
class WeatherException extends RuntimeException {
	private final HttpStatus status;
	private final String code;
	WeatherException(HttpStatus status, String code, String message) {
		super(message); this.status = status; this.code = code;
	}
	static WeatherException invalid() {
		return new WeatherException(HttpStatus.BAD_GATEWAY, "INVALID_WEATHER_RESPONSE", "Invalid weather provider response");
	}
	static WeatherException unavailable() {
		// RestClient 예외에는 인증키가 포함된 URL이 들어갈 수 있다. 원격 예외·원문을 외부 응답에 연결하지 않는다.
		return new WeatherException(HttpStatus.SERVICE_UNAVAILABLE, "WEATHER_SERVICE_UNAVAILABLE", "Weather provider is temporarily unavailable");
	}
}

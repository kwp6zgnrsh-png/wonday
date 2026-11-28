package com.wonday.insurance.weather;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 원격 예외 메시지 대신 허용된 코드만 이력·응답에 사용한다. 인증키가 담긴 URL·원문을 보관하지 않는다. */
enum WeatherCollectionFailure {
	WEATHER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Weather provider is temporarily unavailable"),
	WEATHER_PROVIDER_REJECTED(HttpStatus.BAD_GATEWAY, "Weather provider rejected the request; check API authorization and parameters"),
	INVALID_WEATHER_RESPONSE(HttpStatus.BAD_GATEWAY, "Invalid weather provider response"),
	WEATHER_API_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Weather API credential is not configured"),
	WEATHER_COLLECTION_ACCESS_CHANGED(HttpStatus.FORBIDDEN, "Admin access changed during collection"),
	WEATHER_COLLECTION_STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Weather collection storage is temporarily unavailable"),
	WEATHER_COLLECTION_RUN_EXPIRED(HttpStatus.CONFLICT, "Weather collection run expired; retry with a new request key"),
	WEATHER_COLLECTION_RUN_SUPERSEDED(HttpStatus.CONFLICT, "A newer weather collection attempt has replaced this run"),
	WEATHER_COLLECTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Weather collection failed");

	private final HttpStatus status;
	private final String message;
	WeatherCollectionFailure(HttpStatus status, String message) { this.status = status; this.message = message; }
	WeatherException exception(long jobId, Long attemptId) { return new WeatherException(status, name(), message, jobId, attemptId); }
	static WeatherCollectionFailure from(RuntimeException error) {
		if (error instanceof WeatherException weather) {
			try { return valueOf(weather.getCode()); } catch (IllegalArgumentException ignored) { return WEATHER_COLLECTION_FAILED; }
		}
		if (error instanceof ResponseStatusException access
			&& (access.getStatusCode().value() == 401 || access.getStatusCode().value() == 403)) return WEATHER_COLLECTION_ACCESS_CHANGED;
		return error instanceof DataAccessException ? WEATHER_COLLECTION_STORAGE_UNAVAILABLE : WEATHER_COLLECTION_FAILED;
	}
}

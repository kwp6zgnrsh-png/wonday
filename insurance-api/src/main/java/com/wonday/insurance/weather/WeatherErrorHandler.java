package com.wonday.insurance.weather;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = WeatherCollectionApi.class)
class WeatherErrorHandler {
	@ExceptionHandler(WeatherException.class)
	ResponseEntity<Error> failed(WeatherException exception) {
		return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore()).body(new Error(exception.getCode(), exception.getMessage()));
	}
	record Error(String code, String message) {}
}

package com.wonday.insurance.weather;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

final class StationCoordinates {
	private StationCoordinates() {}
	static void validate(BigDecimal latitude, BigDecimal longitude) {
		if (latitude == null || longitude == null || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
			|| latitude.compareTo(BigDecimal.valueOf(90)) > 0 || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
			|| longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
			throw new WeatherException(HttpStatus.BAD_REQUEST, "INVALID_WORK_COORDINATES", "Valid latitude and longitude are required");
		}
	}
	static double distanceMeters(BigDecimal fromLatitude, BigDecimal fromLongitude, BigDecimal toLatitude, BigDecimal toLongitude) {
		double latitude = Math.toRadians(fromLatitude.doubleValue());
		double other = Math.toRadians(toLatitude.doubleValue());
		double lat = Math.sin((other - latitude) / 2);
		double lon = Math.sin(Math.toRadians(toLongitude.subtract(fromLongitude).doubleValue()) / 2);
		double a = lat * lat + Math.cos(latitude) * Math.cos(other) * lon * lon;
		return 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
	}
}

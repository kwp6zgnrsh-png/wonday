package com.wonday.insurance.platform;

import jakarta.validation.Validator;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import static com.wonday.insurance.platform.FarmWorkResponse.*;

@Component
@RequiredArgsConstructor
class FarmWorkClient {

	private final RestClient farmRestClient;
	private final FarmPlatformProperties properties;
	private final Validator validator;

	Page changes(long workerId, String cursor, int limit) {
		Page page = get(workerId, "/work-changes", cursor, limit, Page.class);
		if (page.workerId() != workerId || page.items().size() > limit
			|| (page.hasMore() && (page.items().isEmpty() || page.nextCursor().equals(cursor)))) {
			throw invalidResponse();
		}
		for (Change change : page.items()) {
			if (change.updatedAt().isBefore(change.recordedAt())
				|| (change.reasonCode() != ReasonCode.WEATHER && change.weatherReasonCode() != null)) {
				throw invalidResponse();
			}
		}
		return page;
	}

	Assignment assignment(long workerId, long assignmentId) {
		Assignment work = get(workerId, "/work-assignments/" + assignmentId, null, null, Assignment.class);
		return validateAssignment(work, workerId, assignmentId);
	}

	static Assignment validateAssignment(Assignment work, long workerId, long assignmentId) {
		if (work.workerId() != workerId || work.assignmentId() != assignmentId
			|| !work.agreedEndAt().isAfter(work.agreedStartAt())) {
			throw invalidResponse();
		}
		try {
			ZoneId zone = ZoneId.of(work.agreedTimeZone());
			if (!work.agreedStartAt().atZoneSameInstant(zone).toLocalDate().equals(work.agreedWorkDate())) {
				throw invalidResponse();
			}
		} catch (DateTimeException exception) {
			throw invalidResponse();
		}
		if (work.agreedBreakStartAt() == null && work.agreedBreakEndAt() == null) {
			return work;
		}
		if (work.agreedBreakStartAt() == null || work.agreedBreakEndAt() == null
			|| work.agreedBreakStartAt().isBefore(work.agreedStartAt())
			|| work.agreedBreakEndAt().isAfter(work.agreedEndAt())
			|| !work.agreedBreakEndAt().isAfter(work.agreedBreakStartAt())) {
			throw invalidResponse();
		}
		return work;
	}

	private <T> T get(long workerId, String path, String cursor, Integer limit, Class<T> type) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
				"FARM_SERVICE_UNAVAILABLE", "Farm service token is not configured");
		}
		T body;
		try {
			body = farmRestClient.get().uri(builder -> {
				builder.path("/api/internal/insurance/workers/{workerId}" + path);
				if (cursor != null) builder.queryParam("cursor", "{cursor}");
				if (limit != null) builder.queryParam("limit", limit);
				return cursor == null ? builder.build(workerId) : builder.build(workerId, cursor);
			}).headers(headers -> headers.setBearerAuth(properties.serviceToken()))
				.accept(MediaType.APPLICATION_JSON).retrieve().body(type);
		} catch (RestClientResponseException exception) {
			// Keep the upstream body and credentials out of scheduler logs.
			throw new PlatformVerificationException(HttpStatus.BAD_GATEWAY,
				"FARM_WORK_HTTP_" + exception.getStatusCode().value(), "Farm work request was rejected");
		} catch (RestClientException exception) {
			if (exception instanceof UnknownContentTypeException
				|| exception.contains(HttpMessageConversionException.class)) {
				throw invalidResponse();
			}
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
				"FARM_SERVICE_UNAVAILABLE", "Farm work service is temporarily unavailable");
		}
		if (body == null || !validator.validate(body).isEmpty()) throw invalidResponse();
		return body;
	}

	static PlatformVerificationException invalidResponse() {
		return new PlatformVerificationException(HttpStatus.BAD_GATEWAY,
			"INVALID_PLATFORM_RESPONSE", "Invalid farm work response");
	}
}

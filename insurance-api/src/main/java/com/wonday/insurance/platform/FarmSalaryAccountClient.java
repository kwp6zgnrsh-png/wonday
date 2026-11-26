package com.wonday.insurance.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
class FarmSalaryAccountClient {

	private final RestClient farmRestClient;
	private final FarmPlatformProperties properties;

	SalaryAccountStatus find(long workerId) {
		if (workerId <= 0) throw new IllegalArgumentException("Worker ID must be positive");
		if (!properties.salaryAccountQueryEnabled()) {
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
				"SALARY_ACCOUNT_QUERY_NOT_READY", "Farm salary account query is not enabled");
		}
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) throw unavailable();
		try {
			JsonNode body = farmRestClient.get()
				.uri("/api/internal/insurance/workers/{workerId}/salary-account", workerId)
				.headers(headers -> headers.setBearerAuth(properties.serviceToken()))
				.accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
			return validate(body, workerId);
		} catch (RestClientResponseException exception) {
			// 404와 미등록(200/false)을 구분한다. 원격 오류 본문·인증값은 반환하거나 로그에 남기지 않는다.
			if (exception.getStatusCode().value() == 404) {
				throw new PlatformVerificationException(HttpStatus.NOT_FOUND,
					"WORKER_NOT_FOUND", "The linked farm worker was not found");
			}
			throw unavailable();
		} catch (RestClientException exception) {
			if (exception instanceof UnknownContentTypeException || exception.contains(HttpMessageConversionException.class)) {
				throw invalidResponse();
			}
			throw unavailable();
		}
	}

	private static SalaryAccountStatus validate(JsonNode body, long expectedWorkerId) {
		if (body == null || !body.isObject()) throw invalidResponse();
		JsonNode worker = body.get("workerId");
		JsonNode registered = body.get("registered");
		JsonNode bank = body.get("bankCode");
		if (worker == null || !worker.isIntegralNumber() || !worker.canConvertToLong()
			|| worker.longValue() != expectedWorkerId || registered == null || !registered.isBoolean() || bank == null) {
			throw invalidResponse();
		}
		if (!registered.booleanValue()) {
			if (!bank.isNull()) throw invalidResponse();
			return new SalaryAccountStatus(false, null);
		}
		// 문자열로 유지해 우리은행 "020" 같은 코드의 앞자리 0을 보존한다. 타 은행도 동일하게 처리한다.
		if (!bank.isString() || !bank.stringValue().matches("[0-9]{3}")) throw invalidResponse();
		return new SalaryAccountStatus(true, bank.stringValue());
	}

	private static PlatformVerificationException invalidResponse() {
		return new PlatformVerificationException(HttpStatus.BAD_GATEWAY,
			"INVALID_PLATFORM_RESPONSE", "Invalid farm salary account response");
	}

	private static PlatformVerificationException unavailable() {
		return new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Farm salary account query is temporarily unavailable");
	}
}

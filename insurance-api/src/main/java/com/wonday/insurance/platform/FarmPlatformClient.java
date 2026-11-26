package com.wonday.insurance.platform;

import com.wonday.insurance.member.NationalityType;
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
public class FarmPlatformClient {

	private final RestClient farmRestClient;
	private final FarmPlatformProperties properties;

	public VerifiedWorker verify(WorkerIdentity identity) {
		boolean v2 = properties.workerVerificationV2Enabled();
		if (!v2 && identity.nationalityType() == NationalityType.FOREIGN) {
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
				"FOREIGN_VERIFICATION_NOT_READY", "Foreign worker verification requires the farm V2 integration");
		}
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw unavailable();
		}
		try {
			// V2 준비 전에는 기존 내국인 API를 유지한다. V2 오류를 기존 API로 자동 재전송하지 않는다.
			JsonNode response = farmRestClient.post()
				.uri(v2 ? "/api/internal/insurance/v2/worker-verifications" : "/api/internal/insurance/worker-verifications")
				.headers(headers -> headers.setBearerAuth(properties.serviceToken()))
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.body(v2 ? identity : new LegacyIdentity(identity.name(), identity.phone(), identity.identityNumber()))
				.retrieve().body(JsonNode.class);
			VerifiedWorker worker = readResponse(response, v2);
			if (!identity.name().equals(worker.name())
				|| !identity.phone().equals(worker.phone())) {
				throw invalidResponse();
			}
			if (worker.nationalityType() != identity.nationalityType()) throw invalidResponse();
			return worker;
		} catch (RestClientResponseException exception) {
			switch (exception.getStatusCode().value()) {
				case 400 -> throw new PlatformVerificationException(HttpStatus.BAD_REQUEST,
					"INVALID_REQUEST", "Worker identity request was rejected");
				case 404 -> throw new PlatformVerificationException(HttpStatus.NOT_FOUND,
					"WORKER_IDENTITY_NOT_MATCHED", "No matching worker was found");
				case 409 -> throw new PlatformVerificationException(HttpStatus.CONFLICT,
					"WORKER_IDENTITY_AMBIGUOUS", "More than one worker matched");
				default -> throw unavailable();
			}
		} catch (RestClientException exception) {
			if (exception instanceof UnknownContentTypeException
				|| exception.contains(HttpMessageConversionException.class)) {
				throw invalidResponse();
			}
			throw unavailable();
		}
	}

	private static VerifiedWorker readResponse(JsonNode response, boolean v2) {
		if (response == null || !response.isObject()) throw invalidResponse();
		JsonNode id = response.get("workerId");
		JsonNode name = response.get("name");
		JsonNode phone = response.get("phone");
		if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0
			|| name == null || !name.isString() || phone == null || !phone.isString()) throw invalidResponse();
		NationalityType nationality = NationalityType.DOMESTIC;
		JsonNode type = response.get("nationalityType");
		if (v2 || type != null) {
			if (type == null || !type.isString()) throw invalidResponse();
			try {
				nationality = NationalityType.valueOf(type.stringValue());
			} catch (IllegalArgumentException exception) {
				throw invalidResponse();
			}
		}
		return new VerifiedWorker(id.longValue(), name.stringValue(), phone.stringValue(), nationality);
	}

	private static PlatformVerificationException invalidResponse() {
		return new PlatformVerificationException(HttpStatus.BAD_GATEWAY,
			"INVALID_PLATFORM_RESPONSE", "Invalid worker verification response");
	}

	private static PlatformVerificationException unavailable() {
		return new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Worker verification is temporarily unavailable");
	}

	public record VerifiedWorker(long workerId, String name, String phone, NationalityType nationalityType) {
		public VerifiedWorker(long workerId, String name, String phone) {
			this(workerId, name, phone, NationalityType.DOMESTIC);
		}
		@Override public String toString() { return "VerifiedWorker[redacted]"; }
	}

	private record LegacyIdentity(String name, String phone, String residentRegistrationNumber) {
		@Override public String toString() { return "LegacyIdentity[redacted]"; }
	}
}

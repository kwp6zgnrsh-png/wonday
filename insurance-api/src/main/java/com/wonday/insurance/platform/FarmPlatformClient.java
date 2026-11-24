package com.wonday.insurance.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
public class FarmPlatformClient {

	private final RestClient farmRestClient;
	private final FarmPlatformProperties properties;

	public VerifiedWorker verify(WorkerIdentity identity) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw unavailable();
		}
		try {
			VerifiedWorker worker = farmRestClient.post()
				.uri("/api/internal/insurance/worker-verifications")
				.headers(headers -> headers.setBearerAuth(properties.serviceToken()))
				.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.body(identity).retrieve().body(VerifiedWorker.class);
			if (worker == null || worker.workerId() <= 0 || !identity.name().equals(worker.name())
				|| !identity.phone().equals(worker.phone())) {
				throw new PlatformVerificationException(HttpStatus.BAD_GATEWAY,
					"INVALID_PLATFORM_RESPONSE", "Invalid worker verification response");
			}
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
			throw unavailable();
		}
	}

	private static PlatformVerificationException unavailable() {
		return new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Worker verification is temporarily unavailable");
	}

	public record VerifiedWorker(long workerId, String name, String phone) {}
}

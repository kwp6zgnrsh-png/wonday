package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.dto.VerifiedWorkerResponse;
import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerVerificationService {
	private final WorkerVerificationRepository workers;

	public WorkerVerificationService(WorkerVerificationRepository workers) { this.workers = workers; }

	@Transactional(readOnly = true)
	public VerifiedWorkerResponse verify(WorkerVerificationRequest request) {
		String name = request.name() == null ? "" : request.name().strip();
		String phone = normalizeNumber(request.phone());
		String residentNumber = normalizeNumber(request.residentRegistrationNumber());
		if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)
				|| !phone.matches("[0-9]{10,11}") || !residentNumber.matches("[0-9]{13}")) {
			throw InsuranceApiException.invalidRequest();
		}
		var matches = workers.findMatches(name, phone, residentNumber);
		if (matches.isEmpty()) {
			throw new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED",
				"입력한 정보와 일치하는 근로자를 확인할 수 없습니다.");
		}
		if (matches.size() > 1) {
			throw new InsuranceApiException(HttpStatus.CONFLICT, "WORKER_IDENTITY_AMBIGUOUS",
				"입력한 정보로 근로자를 한 명으로 확인할 수 없습니다.");
		}
		var match = matches.getFirst();
		return new VerifiedWorkerResponse(match.getWorkerId(), match.getName(), match.getPhone());
	}

	private static String normalizeNumber(String value) {
		return value == null ? "" : value.replaceAll("(?U)[\\s-]", "");
	}
}

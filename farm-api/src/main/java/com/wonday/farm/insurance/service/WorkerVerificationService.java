package com.wonday.farm.insurance.service;

import java.util.List;

import com.wonday.farm.insurance.dto.VerifiedWorkerResponse;
import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.IdentityLookupMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerVerificationService {
	private final WorkerVerificationRepository workers;

	private final IdentityCrypto crypto;
	private final IdentityLookupMode lookupMode;

	public WorkerVerificationService(WorkerVerificationRepository workers, IdentityCrypto crypto,
			@Value("${farm.identity.lookup-mode:LEGACY}") IdentityLookupMode lookupMode) {
		this.workers = workers;
		this.crypto = crypto;
		this.lookupMode = lookupMode;
	}

	@Transactional(readOnly = true)
	public VerifiedWorkerResponse verify(WorkerVerificationRequest request) {
		String name = request.name() == null ? "" : request.name().strip();
		String phone = normalizeNumber(request.phone());
		String residentNumber = normalizeNumber(request.residentRegistrationNumber());
		if (name.isBlank() || name.length() > 100 || name.codePoints().anyMatch(Character::isISOControl)
				|| !phone.matches("[0-9]{10,11}") || !residentNumber.matches("[0-9]{13}")) {
			throw InsuranceApiException.invalidRequest();
		}
		List<WorkerVerificationRepository.Match> matches;
		if (lookupMode == IdentityLookupMode.ENCRYPTED) {
			try {
				matches = workers.findDomesticByIdentityHmac(name, phone, crypto.blindIndex(residentNumber));
			} catch (IdentityCryptoUnavailableException exception) {
				// 조회 전환 후 키 오류가 나도 평문 조회로 자동 후퇴하지 않는다.
				throw InsuranceApiException.unavailable();
			}
		} else {
			// 기본 호환 모드는 이관 확인 전 기존 API를 유지한다. 번호를 이관·수정하지 않는다.
			matches = workers.findMatches(name, phone, residentNumber);
		}
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

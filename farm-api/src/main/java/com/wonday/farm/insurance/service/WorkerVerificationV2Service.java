package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.IdentityNumber;
import com.wonday.farm.member.identity.NationalityType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerVerificationV2Service {
	private final WorkerVerificationRepository workers;
	private final IdentityCrypto crypto;

	public WorkerVerificationV2Service(WorkerVerificationRepository workers, IdentityCrypto crypto) {
		this.workers = workers;
		this.crypto = crypto;
	}

	@Transactional(readOnly = true)
	public VerifiedWorkerV2Response verify(WorkerVerificationV2Request request) {
		if (request == null) throw InsuranceApiException.invalidRequest();
		String name = bounded(request.name(), 100).strip();
		String phone = bounded(request.phone(), 100).replaceAll("(?U)[\\s-]", "");
		NationalityType nationality;
		String number;
		try {
			nationality = NationalityType.valueOf(bounded(request.nationalityType(), 16));
			number = IdentityNumber.normalize(request.identityNumber());
		} catch (IllegalArgumentException exception) {
			throw InsuranceApiException.invalidRequest();
		}
		if (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl) || !phone.matches("[0-9]{10,11}")) {
			throw InsuranceApiException.invalidRequest();
		}
		String hmac;
		try {
			hmac = crypto.blindIndex(number);
		} catch (IdentityCryptoUnavailableException exception) {
			// 키 오류가 나도 원문 조회·V1 재호출로 우회하지 않는다.
			throw InsuranceApiException.unavailable();
		}
		// 번호 종류는 요청에서 받지 않고 국적에 맞춰 서버가 결정한다.
		var matches = workers.findByNationalityAndIdentityHmac(name, phone, nationality.name(),
			nationality.identityType().name(), hmac);
		if (matches.isEmpty()) {
			throw new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED",
				"입력한 정보와 일치하는 근로자를 확인할 수 없습니다.");
		}
		if (matches.size() > 1) {
			throw new InsuranceApiException(HttpStatus.CONFLICT, "WORKER_IDENTITY_AMBIGUOUS",
				"입력한 정보로 근로자를 한 명으로 확인할 수 없습니다.");
		}
		var match = matches.getFirst();
		NationalityType storedNationality;
		try {
			storedNationality = NationalityType.valueOf(match.getNationalityType());
		} catch (IllegalArgumentException | NullPointerException exception) {
			throw InsuranceApiException.unavailable();
		}
		if (storedNationality != nationality || match.getWorkerId() <= 0) throw InsuranceApiException.unavailable();
		return new VerifiedWorkerV2Response(match.getWorkerId(), match.getName(), match.getPhone(), storedNationality);
	}

	private static String bounded(String value, int maximum) {
		if (value == null || value.length() > maximum) throw InsuranceApiException.invalidRequest();
		return value;
	}
}

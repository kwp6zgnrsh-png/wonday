package com.wonday.farm.insurance.service;

import com.wonday.farm.insurance.dto.WorkerSalaryAccountResponse;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerSalaryAccountRepository;
import com.wonday.farm.member.MemberRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerSalaryAccountQueryService {
	private final WorkerSalaryAccountRepository accounts;

	public WorkerSalaryAccountQueryService(WorkerSalaryAccountRepository accounts) {
		this.accounts = accounts;
	}

	@Transactional(readOnly = true)
	public WorkerSalaryAccountResponse find(long workerId) {
		if (workerId <= 0) throw InsuranceApiException.invalidRequest();
		var status = accounts.findStatus(workerId).orElseThrow(WorkerSalaryAccountQueryService::workerNotFound);
		// DB 문자열의 대소문자·미등록 역할을 허용하지 않는다. 고용주에게 계좌가 있어도 404다.
		if (!MemberRole.WORKER.name().equals(status.getMemberRole())) throw workerNotFound();
		if (status.getWorkerId() != workerId) throw InsuranceApiException.unavailable();

		Long accountMemberId = status.getAccountMemberId();
		String bankCode = status.getBankCode();
		if (accountMemberId == null) {
			// 계좌가 없다는 정상 상태와 잘못된 조회 결과를 구분한다.
			if (bankCode != null) throw InsuranceApiException.unavailable();
			return new WorkerSalaryAccountResponse(workerId, false, null);
		}
		if (accountMemberId.longValue() != workerId || bankCode == null || !bankCode.matches("[0-9]{3}")) {
			throw InsuranceApiException.unavailable();
		}
		// registered는 저장된 계좌 행의 존재 여부다. 실계좌·예금주·은행 실재 확인이나 복호화는 하지 않는다.
		return new WorkerSalaryAccountResponse(workerId, true, bankCode);
	}

	private static InsuranceApiException workerNotFound() {
		return new InsuranceApiException(HttpStatus.NOT_FOUND, "WORKER_NOT_FOUND", "해당 근로자를 찾을 수 없습니다.");
	}
}

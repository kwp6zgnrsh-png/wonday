package com.wonday.insurance.contract;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContractClaimCatalog {
	private final ContractRepository contracts;

	@Transactional(propagation = Propagation.MANDATORY)
	public List<Long> lockCoveredContractIds(long memberId, LocalDateTime occurredAt) {
		return contracts.lockCoveredContracts(memberId, occurredAt).stream().map(InsuranceContract::getId).toList();
	}
}

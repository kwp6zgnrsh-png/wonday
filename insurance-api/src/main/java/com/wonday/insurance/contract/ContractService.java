package com.wonday.insurance.contract;

import com.wonday.insurance.platform.FarmPlatformClient;
import com.wonday.insurance.product.ProductEnrollmentCatalog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class ContractService {

	private final FarmPlatformClient farm;
	private final ProductEnrollmentCatalog products;
	private final ContractWriter writer;
	private final ContractRepository contracts;
	private final Clock clock;

	// Verification completes before the transaction that writes contact, link and contract.
	ContractView enroll(long memberId, ContractEnrollmentRequest request) {
		products.requireAvailable(request.productVersionId(), now());
		var worker = farm.verify(request.identity());
		long id;
		try {
			id = writer.create(memberId, request, worker);
		} catch (DataIntegrityViolationException exception) {
			if (exception.getCause() instanceof ConstraintViolationException violation
				&& violation.getConstraintName() != null
				&& violation.getConstraintName().contains("uq_active_")) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Farm account is already linked");
			}
			throw exception;
		}
		return detail(memberId, id);
	}

	@Transactional(readOnly = true)
	public List<ContractView> list(long memberId) {
		return contracts.findOwned(memberId, null, now());
	}

	@Transactional(readOnly = true)
	public ContractView detail(long memberId, long contractId) {
		return contracts.findOwned(memberId, contractId, now()).stream().findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract does not exist"));
	}

	private LocalDateTime now() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}
}

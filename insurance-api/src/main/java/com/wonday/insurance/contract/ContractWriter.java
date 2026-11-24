package com.wonday.insurance.contract;

import com.wonday.insurance.member.MemberEnrollmentService;
import com.wonday.insurance.platform.FarmPlatformClient.VerifiedWorker;
import com.wonday.insurance.platform.PlatformLinkService;
import com.wonday.insurance.product.ProductEnrollmentCatalog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
class ContractWriter {

	private final MemberEnrollmentService members;
	private final ProductEnrollmentCatalog products;
	private final PlatformLinkService links;
	private final ContractRepository contracts;
	private final Clock clock;

	@Transactional
	public long create(long memberId, ContractEnrollmentRequest request, VerifiedWorker worker) {
		members.lockWorkerAndSaveContact(memberId, worker.phone(), request.identity().residentRegistrationNumber());
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		var product = products.requireAvailable(request.productVersionId(), now);
		if (contracts.hasCurrentContract(memberId, product.productId(), now)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is already covered by a current contract");
		}
		links.link(memberId, worker.workerId(), now);
		LocalDateTime end = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Seoul"))
			.plusMonths(3).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		return contracts.saveAndFlush(new InsuranceContract(memberId, product.versionId(), now, now, end)).getId();
	}
}

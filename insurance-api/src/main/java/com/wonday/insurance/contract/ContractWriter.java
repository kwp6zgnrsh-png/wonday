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
		// 동일 회원의 동시 가입을 직렬화하고 연락처·동의 연결·계약을 한 번에 저장한다.
		members.lockWorkerAndSaveContact(memberId, request.identity(), worker.phone());
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
		var product = products.requireAvailable(request.productVersionId(), now);
		if (contracts.hasCurrentContract(memberId, product.productId(), now)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Product is already covered by a current contract");
		}
		links.link(memberId, worker.workerId(), now);
		// 보장 3개월은 GMT+9의 달력 기준으로 계산한다. 계산 후에는 DB 저장 기준인 UTC로 돌린다.
		LocalDateTime end = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("GMT+9"))
			.plusMonths(3).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
		return contracts.saveAndFlush(new InsuranceContract(memberId, product.versionId(), now, now, end)).getId();
	}
}

package com.wonday.insurance.platform;

import com.wonday.insurance.member.MemberAccessService;
import com.wonday.insurance.member.MemberEnrollmentService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PlatformAccountService {

	private final MemberEnrollmentService members;
	private final MemberAccessService access;
	private final FarmPlatformClient farm;
	private final FarmPlatformProperties properties;
	private final PlatformAccountWriter writer;
	private final PlatformLinkRepository links;

	PlatformLinkStatus register(long memberId, PlatformLinkRequest request) {
		members.requireMatchingNationality(memberId, request.identity().nationalityType());
		if (!properties.consentVersion().equals(request.consentVersion())) {
			throw new PlatformVerificationException(HttpStatus.CONFLICT, "CONSENT_VERSION_MISMATCH",
				"Please review the current farm platform consent version");
		}
		// 보험 상품이나 계약을 만들지 않고 본인 확인만 수행한다. 원격 호출 중에는 DB 쓰기 트랜잭션을 열지 않는다.
		var worker = farm.verify(request.identity());
		try {
			return writer.save(memberId, request.identity(), worker);
		} catch (DataIntegrityViolationException exception) {
			// 다른 보험 회원이 같은 농업 계정을 동시에 등록한 경우 DB 유일성 제약이 최종 차단한다.
			if (exception.getCause() instanceof ConstraintViolationException violation
				&& violation.getConstraintName() != null && violation.getConstraintName().contains("uq_active_")) {
				throw PlatformLinkService.conflict();
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public PlatformLinkStatus status(long memberId) {
		access.requireWorker(memberId);
		return PlatformLinkStatus.from(links.findByMemberIdAndRevokedAtIsNull(memberId).orElse(null), properties.consentVersion());
	}
}

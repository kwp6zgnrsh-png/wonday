package com.wonday.insurance.member;

import com.wonday.insurance.platform.PlatformVerificationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MemberEnrollmentService {

	private final MemberRepository members;
	private final IdentityCipher cipher;

	@Transactional(readOnly = true)
	public void requireDomesticVerification(long memberId) {
		Member member = members.findById(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		requireDomestic(member);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockWorkerAndSaveContact(long memberId, String phone, String residentRegistrationNumber) {
		Member member = members.findForEnrollment(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		if (member.getRole() != MemberRole.WORKER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker account required");
		}
		requireDomestic(member);
		// 회원 잠금과 기존 계약 트랜잭션 안에서 암호문만 저장한다. 평문 컬럼에는 더 이상 쓰지 않는다.
		member.updateDomesticEnrollmentContact(phone,
			cipher.encrypt(memberId, IdentityType.RESIDENT_REGISTRATION_NUMBER, residentRegistrationNumber));
	}

	private static void requireDomestic(Member member) {
		if (member.getNationalityType() == NationalityType.FOREIGN) {
			// 농업 V2 연동 전에는 외국인등록번호를 기존 주민등록번호 API로 전송하지 않는다.
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
				"FOREIGN_VERIFICATION_NOT_READY", "Foreign worker verification requires the farm V2 integration");
		}
	}
}

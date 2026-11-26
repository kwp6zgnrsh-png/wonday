package com.wonday.insurance.member;

import com.wonday.insurance.platform.PlatformVerificationException;
import com.wonday.insurance.platform.WorkerIdentity;
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
	public void requireMatchingNationality(long memberId, NationalityType nationality) {
		var profile = members.findEnrollmentProfile(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		requireMatchingNationality(profile.getRole(), profile.getNationalityType(), nationality);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void lockWorkerAndSaveContact(long memberId, WorkerIdentity identity, String verifiedPhone) {
		Member member = members.findForEnrollment(memberId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member does not exist"));
		// 원격 확인 전에 검사했어도 그 사이 회원 구분이 바뀔 수 있어 행 잠금을 얻은 후 다시 검사한다.
		requireMatchingNationality(member.getRole(), member.getNationalityType(), identity.nationalityType());
		// 회원 잠금과 기존 계약 트랜잭션 안에서 암호문만 저장한다. 평문 컬럼에는 더 이상 쓰지 않는다.
		member.updateEnrollmentContact(verifiedPhone, identity.nationalityType(),
			cipher.encrypt(memberId, identity.identityType(), identity.identityNumber()));
	}

	private static void requireMatchingNationality(MemberRole role, NationalityType registered, NationalityType requested) {
		if (role != MemberRole.WORKER) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Worker account required");
		}
		// 구분이 없는 기존 회원은 원격 확인 성공 후 확정한다. 이미 정한 구분을 가입 요청으로 덮어쓰지 않는다.
		if (registered != null && registered != requested) {
			throw new PlatformVerificationException(HttpStatus.CONFLICT,
				"NATIONALITY_TYPE_MISMATCH", "Worker identity nationality does not match the insurance member");
		}
	}
}
